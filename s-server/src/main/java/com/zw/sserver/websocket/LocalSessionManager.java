package com.zw.sserver.websocket;

import jakarta.websocket.Session;
import java.util.Set;

/**
 * 本地 WebSocket Session 管理器。
 * <p>
 * 从 ChatEndpoint 的 static ConcurrentHashMap 抽离为独立组件，
 * 负责管理当前 S_Server 实例上的 WebSocket 连接。
 *
 * <h3>补丁 5：线程安全发送约束</h3>
 * {@link #sendToUser(Long, String)} 的实现<b>必须</b>对每个 Session
 * 加锁同步（per-session lock），因为 Tomcat {@code BasicRemote} 非线程安全，
 * 多线程并发发送会抛出
 * {@code IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]}。
 * <p>
 * 发送状态归属于实际 Session，与 ACK、心跳等直接响应共用；不得按用户 ID 单独删除发送锁。
 */
public interface LocalSessionManager {

    /**
     * 注册本地 Session。
     *
     * @param userId  用户 ID
     * @param session WebSocket Session
     */
    void addSession(Long userId, Session session);

    /**
     * 移除本地 Session（仅当 session 匹配时移除）。
     *
     * @param userId  用户 ID
     * @param session 要移除的 WebSocket Session
     */
    void removeSession(Long userId, Session session);

    /**
     * 获取本地 Session，不存在返回 null。
     *
     * @param userId 用户 ID
     * @return 对应的 Session，或 null
     */
    Session getSession(Long userId);

    /**
     * 向指定用户发送消息，返回是否发送成功。
     * <p>
     * <b>补丁 5</b>：实现时必须使用 per-session lock 保证线程安全，
     * 防止本地 WebSocket 线程和 RedisMessageStreamConsumer 监听线程并发发送冲突。
     *
     * @param userId  目标用户 ID
     * @param message 消息文本
     * @return true 表示发送成功，false 表示用户不在线或发送失败
     */
    boolean sendToUser(Long userId, String message);

    /**
     * 获取所有本地在线用户 ID。
     *
     * @return 本地在线用户 ID 集合
     */
    Set<Long> getAllLocalUsers();

    /**
     * 关闭所有本地连接（优雅停机时使用）。
     *
     * @param reason 关闭原因（将作为 Close Frame 的 reason 发送给客户端）
     */
    void closeAll(String reason);
}
