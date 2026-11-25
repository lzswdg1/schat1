package com.zw.sserver.mq;

/**
 * 消息持久化队列消费者
 * <p>
 * 消费 {@code schat:mq:persist}，写入 MySQL，成功后 XACK + 清理 Inbox。
 * 失败最多重试 3 次，指数退避（1s, 2s, 4s）。
 * <p>
 * 【补丁 2】超过最大重试次数后，转移到死信队列并强制 XACK，防止 PEL 无限膨胀。
 */
public interface MessageQueueConsumer {

    /** 启动消费循环 */
    void start();

    /** 停止消费 */
    void stop();
}
