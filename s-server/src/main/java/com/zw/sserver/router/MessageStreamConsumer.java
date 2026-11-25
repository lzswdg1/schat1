package com.zw.sserver.router;

/**
 * 消费本实例的 MessageStream (schat:route:{instanceId})。
 * <p>
 * 使用 Consumer Group 模式，支持宕机恢复：
 * <ol>
 *   <li>启动时先处理 Pending 消息（未确认的历史消息）</li>
 *   <li>然后开始消费新消息</li>
 * </ol>
 */
public interface MessageStreamConsumer {

    /** 启动消费循环 */
    void start();

    /** 停止消费 */
    void stop();
}
