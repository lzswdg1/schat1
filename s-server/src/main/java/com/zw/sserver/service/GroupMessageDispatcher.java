package com.zw.sserver.service;

import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 群消息实时分发器
 * <p>
 * 负责将群消息扇出到所有在线群成员。
 * <p>
 * 【扇出策略】
 * 1. 遍历群成员列表
 * 2. 跳过发送者自己
 * 3. 对每个成员复用现有的 CrossNodeRouter 路由逻辑：
 *    - 本地在线 → 直接投递
 *    - 远程在线 → Redis Stream 跨节点路由
 *    - 离线 → 跳过（客户端按持久化 seq 增量同步兜底）
 * <p>
 * 【为什么用独立线程池？】
 * 1000 人群发一条消息 = 遍历 1000 个成员做路由决策。
 * 如果在 WebSocket 的 onMessage 线程里同步做，会阻塞发送方。
 * 用独立线程池异步扇出，发送方立刻收到 ACK。
 */
@Slf4j
@Service
public class GroupMessageDispatcher {

    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;

    /**
     * 群消息扇出线程池
     * core=4, max=8, queue=5000
     * 一个 1000 人群的扇出任务约 1000 次路由决策，
     * 每次路由决策是微秒级（本地查 Map + 可能的 Redis XADD），
     * 整体耗时约 10-50ms，不会长时间占用线程。
     */
    private final ExecutorService dispatchExecutor = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5000),
            r -> {
                Thread t = new Thread(r, "group-msg-dispatcher");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public GroupMessageDispatcher(CrossNodeRouter crossNodeRouter,
                                  InstanceRegistry instanceRegistry) {
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
    }

    /**
     * 异步扇出群消息到所有在线成员
     *
     * @param groupId   群 ID
     * @param senderId  发送者 ID（跳过）
     * @param memberIds 群成员 ID 列表
     * @param payload   要发送的 JSON 消息体
     */
    public void dispatchAsync(Long groupId, Long senderId, Set<Long> memberIds, String payload) {
        dispatchExecutor.execute(() -> {
            int delivered = 0;
            int skipped = 0;

            for (Long memberId : memberIds) {
                if (memberId.equals(senderId)) continue;

                try {
                    RouteMessage routeMsg = new RouteMessage();
                    routeMsg.setType("GROUP_CHAT");
                    routeMsg.setTargetUserId(memberId);
                    routeMsg.setPayload(payload);
                    routeMsg.setSourceInstanceId(instanceRegistry.getInstanceId());

                    boolean sent = crossNodeRouter.route(routeMsg);
                    if (sent) {
                        delivered++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    log.error("群消息扇出失败: groupId={}, memberId={}", groupId, memberId, e);
                }
            }

            log.info("📢 [群消息扇出] groupId={}, 总成员={}, 投递={}, 离线跳过={}",
                    groupId, memberIds.size(), delivered, skipped);
        });
    }
}
