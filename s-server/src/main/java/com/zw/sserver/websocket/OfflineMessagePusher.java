package com.zw.sserver.websocket;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.dto.InboxMessage;
import com.zw.sserver.model.entity.ChatMessage;
import com.zw.sserver.mq.RedisInboxService;
import com.zw.sserver.service.GroupReadCursorService;
import com.zw.sserver.service.GroupService;
import com.zw.sserver.model.entity.GroupInfo;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 离线消息异步推送器
 * <p>
 * 【为什么要从 ChatEndpoint.onOpen 中抽出来？】
 * <p>
 * 原来的问题：onOpen 里同步查 MySQL + 逐条推送离线消息。
 * 如果用户有 500 条未读，连接建立时 Tomcat 线程被阻塞在：
 *   1. Redis ZRANGEBYSCORE（查 Inbox）
 *   2. MySQL SELECT（查未读消息）
 *   3. 经本地 Session 管理器的统一串行入口逐条推送
 * <p>
 * 后果：
 *   - 单个用户上线慢（要等离线消息全部推完才算连接建立完成）
 *   - 服务重启后大量用户同时重连 → Tomcat 线程池耗尽 → 新用户连不上
 *   - Tomcat 默认 200 个线程，如果每个 onOpen 阻塞 500ms，
 *     200 个并发连接就把线程池占满了
 * <p>
 * 解决方案：
 *   onOpen 只做轻量操作（鉴权 + 注册 Session + 上线），立刻返回。
 *   离线消息拉取和推送丢到独立线程池异步执行。
 * <p>
 * 【线程池设计】
 * 用独立的线程池，不占用 Tomcat 线程。
 * 核心线程 2，最大线程 4，队列 1000。
 * 为什么不用很多线程？因为瓶颈在 MySQL 和 Redis IO，
 * 线程太多反而增加数据库连接压力。
 * <p>
 * 【线程安全】
 * 异步推送时 Session 可能已经关闭（用户连上又立刻断了）。
 * 每次发送前检查连接有效性，并遵循统一的发送背压与超时。
 */
@Component
public class OfflineMessagePusher {

    private static final Logger log = LoggerFactory.getLogger(OfflineMessagePusher.class);

    private final RedisInboxService redisInboxService;
    private final ChatMessageMapper chatMessageMapper;
    private final GroupService groupService;
    private final GroupReadCursorService groupReadCursorService;
    private final LocalSessionManager localSessionManager;
    private final ObjectMapper objectMapper;

    /**
     * 独立线程池：专门用于离线消息拉取和推送
     * <p>
     * 【为什么不用 @Async + Spring 的线程池？】
     * 1. ChatEndpoint 是 @ServerEndpoint，不归 Spring 代理管，@Async 不生效
     * 2. 独立线程池可以精确控制并发数，防止打爆数据库连接池
     * 3. 线程名带前缀，方便 jstack 排查问题
     * <p>
     * 【参数解释】
     * corePoolSize = 2：平时保持 2 个线程
     * maximumPoolSize = 4：高峰期最多 4 个线程
     * keepAliveTime = 60s：空闲线程 60 秒后回收
     * workQueue = 1000：最多排队 1000 个任务
     * 超过 1000 → CallerRunsPolicy → 在调用者线程执行（降级为同步）
     */
    private final ExecutorService offlineExecutor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "offline-msg-pusher");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满了降级为同步执行
    );

    public OfflineMessagePusher(RedisInboxService redisInboxService,
                                ChatMessageMapper chatMessageMapper,
                                GroupService groupService,
                                GroupReadCursorService groupReadCursorService,
                                LocalSessionManager localSessionManager,
                                ObjectMapper objectMapper) {
        this.redisInboxService = redisInboxService;
        this.chatMessageMapper = chatMessageMapper;
        this.groupService = groupService;
        this.groupReadCursorService = groupReadCursorService;
        this.localSessionManager = localSessionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步拉取并推送离线消息
     *
     * @param session   WebSocket 会话（仅用于检查 isOpen 状态）
     * @param authedUid 用户 ID
     */
    public void pushAsync(Session session, Long authedUid) {
        offlineExecutor.execute(() -> {
            try {
                doPush(session, authedUid);
            } catch (Exception e) {
                log.error("❌ [离线推送异常] userId={}", authedUid, e);
            }
        });
    }

    /**
     * 实际的拉取 + 合并 + 推送逻辑
     * <p>
     * 和原来 onOpen 里的逻辑完全一样，只是搬到了独立线程里执行。
     */
    private void doPush(Session session, Long authedUid) {
        // 推送前先检查 Session 是否还活着
        if (session == null || !session.isOpen()) {
            log.debug("Session 已关闭，跳过离线推送: userId={}", authedUid);
            return;
        }

        // 1. 从 Redis Inbox 获取未落库的消息摘要
        List<InboxMessage> inboxMessages = redisInboxService.getInbox(authedUid);

        // 2. 从 MySQL 获取未读消息（is_read < 2 表示未读：0=未送达, 1=已送达）
        //    加 LIMIT 500 防止长期不上线的用户一次性加载过多消息导致 OOM
        QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("receiver_id", authedUid)
                .lt("is_read", 2)
                .orderByAsc("send_time")
                .last("LIMIT 500");
        List<ChatMessage> mysqlMessages = chatMessageMapper.selectList(queryWrapper);

        // 3. 合并去重：以 messageId 为 key，MySQL 优先（有完整 cipherContent）
        Map<Long, String> mergedMessages = new LinkedHashMap<>();

        if (inboxMessages != null) {
            for (InboxMessage inbox : inboxMessages) {
                String wrappedJson = wrapInboxMessage(inbox);
                if (wrappedJson != null) {
                    mergedMessages.put(inbox.getMessageId(), wrappedJson);
                }
            }
        }

        if (mysqlMessages != null) {
            for (ChatMessage msg : mysqlMessages) {
                // 包装为 NEW_MESSAGE 格式
                String wrapped = wrapMysqlOfflineMessage(msg);
                mergedMessages.put(msg.getMessageId(), wrapped);
            }
        }

        // 4. 按 messageId 升序排列并推送
        if (!mergedMessages.isEmpty()) {
            List<Map.Entry<Long, String>> sorted = mergedMessages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());

            log.info("📬 [离线推送] 合并后发现 {} 条未读消息（Inbox: {}, MySQL: {}），正在推送给 {} ...",
                    sorted.size(),
                    inboxMessages != null ? inboxMessages.size() : 0,
                    mysqlMessages != null ? mysqlMessages.size() : 0,
                    authedUid);

            for (Map.Entry<Long, String> entry : sorted) {
                // 每条消息推送前都检查 Session 状态
                if (!session.isOpen()) {
                    log.warn("📴 [离线推送中断] 用户 {} 的 Session 已关闭，停止推送", authedUid);
                    return;
                }
                localSessionManager.sendToUser(authedUid, entry.getValue());
            }
        }

        // ========== 群聊未读摘要推送 ==========
        pushGroupUnreadSummary(session, authedUid);
    }

    /**
     * 推送群聊未读摘要
     * <p>
     * 用户上线时，推送一条汇总消息告知各群未读数，
     * 前端根据此消息更新群列表的未读角标。
     * 不逐条推送群离线消息（群消息量太大，由前端按需拉取历史）。
     */
    private void pushGroupUnreadSummary(Session session, Long authedUid) {
        try {
            if (session == null || !session.isOpen()) return;

            List<GroupInfo> groups = groupService.getUserGroups(authedUid);
            if (groups == null || groups.isEmpty()) return;

            Set<Long> groupIds = groups.stream()
                    .map(GroupInfo::getGroupId)
                    .collect(Collectors.toSet());

            Map<Long, Integer> unreadMap = groupReadCursorService.getAllUnreadCounts(authedUid, groupIds);
            if (unreadMap.isEmpty()) return;

            // 构建未读计数 JSON（新前端格式）
            // { "type": "UNREAD_COUNTS", "data": { "session_g100": 5, "session_g200": 3 } }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"UNREAD_COUNTS\",\"data\":{");
            boolean first = true;
            for (Map.Entry<Long, Integer> entry : unreadMap.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"group_").append(entry.getKey()).append("\":").append(entry.getValue());
                first = false;
            }
            sb.append("}}");

            localSessionManager.sendToUser(authedUid, sb.toString());
            log.info("📢 [群未读摘要] 用户 {} 有 {} 个群存在未读消息", authedUid, unreadMap.size());
        } catch (Exception e) {
            log.error("群未读摘要推送失败: userId={}", authedUid, e);
        }
    }

    /**
     * 将 Inbox 摘要包装成新前端期望的 NEW_MESSAGE 格式。
     * { "type": "NEW_MESSAGE", "data": { "id": ..., "senderId": ..., "content": ..., ... } }
     */
    private String wrapInboxMessage(InboxMessage inbox) {
        String brief = inbox.getBriefContent();
        if (brief == null) return null;

        try {
            Long senderId = null;
            String msgType = "text";
            String content = "";

            String[] parts = brief.split("\\|", 3);
            for (String part : parts) {
                if (part.startsWith("sender:")) {
                    senderId = Long.parseLong(part.substring(7));
                } else if (part.startsWith("type:")) {
                    msgType = part.substring(5).toLowerCase();
                } else {
                    content = part;
                }
            }

            return String.format(
                    "{\"type\":\"NEW_MESSAGE\",\"data\":{\"id\":\"%d\",\"sessionId\":\"%s\",\"type\":\"%s\",\"senderId\":\"%s\",\"content\":\"%s\",\"timestamp\":%d,\"recalled\":false}}",
                    inbox.getMessageId(),
                    senderId != null ? senderId.toString() : "unknown",
                    msgType,
                    senderId != null ? senderId.toString() : "unknown",
                    escapeJson(content),
                    inbox.getTimestamp() > 0 ? inbox.getTimestamp() : System.currentTimeMillis()
            );
        } catch (Exception e) {
            log.warn("Inbox 摘要解析失败，跳过: messageId={}, brief={}", inbox.getMessageId(), brief, e);
            return null;
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    /**
     * 将 MySQL 离线消息包装为新前端期望的 NEW_MESSAGE 格式
     * 解析 cipherContent JSON 提取实际 content 和 msgType
     */
    private String wrapMysqlOfflineMessage(ChatMessage msg) {
        String content = "";
        String msgType = "text";
        boolean recalled = false;
        if (msg.getCipherContent() != null && !msg.getCipherContent().isEmpty()) {
            try {
                JsonNode node = objectMapper.readTree(msg.getCipherContent());
                if (node.has("content")) content = node.get("content").asText("");
                if (node.has("msgType")) msgType = node.get("msgType").asText("TEXT").toLowerCase();
                recalled = node.path("recalled").asBoolean(false);
            } catch (Exception e) {
                content = msg.getCipherContent();
            }
        }
        return String.format(
                "{\"type\":\"NEW_MESSAGE\",\"data\":{\"id\":\"%d\",\"sessionId\":\"%d\",\"type\":\"%s\",\"senderId\":\"%d\",\"content\":\"%s\",\"timestamp\":%d,\"seq\":\"%d\",\"recalled\":%s}}",
                msg.getMessageId(),
                msg.getSenderId(),
                escapeJson(msgType),
                msg.getSenderId(),
                escapeJson(content),
                msg.getSendTime() != null ? msg.getSendTime().getTime() : System.currentTimeMillis(),
                msg.getSeq() != null ? msg.getSeq() : 0L,
                recalled
        );
    }
}
