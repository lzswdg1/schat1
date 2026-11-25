package com.zw.sserver.mq;

import com.zw.sserver.model.dto.InboxMessage;

import java.util.List;

/**
 * 用户 Redis 收件箱管理
 * <p>
 * Redis Key: schat:inbox:{userId} (ZSet, score=消息时间戳)
 * TTL: 7 天
 * <p>
 * 【补丁 3：ZSet TTL 刷新风暴优化】
 * 禁止每次 ZADD 都执行 EXPIRE。使用 Lua 脚本原子操作：
 * 仅当 Key 首次创建时（ZADD 返回新增数 > 0 且 Key 之前不存在）才设置 TTL，
 * 避免高频聊天场景下的 TTL 刷新风暴。
 */
public interface RedisInboxService {

    /**
     * 写入简略版消息到用户 Inbox
     */
    void addToInbox(Long userId, Long messageId, String briefContent, long timestamp);

    /** 从 Inbox 移除指定消息（落库成功后调用） */
    void removeFromInbox(Long userId, Long messageId);

    /** 获取用户 Inbox 中所有消息 */
    List<InboxMessage> getInbox(Long userId);
}
