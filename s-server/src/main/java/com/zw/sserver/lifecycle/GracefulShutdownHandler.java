package com.zw.sserver.lifecycle;

import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.websocket.LocalSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅停机处理器
 * <p>
 * 实现 {@link SmartLifecycle}，phase 设为 {@code Integer.MAX_VALUE}，
 * 确保在所有其他 SmartLifecycle 组件（MessageStreamConsumer phase=10、
 * MessageQueueConsumer phase=15）之前执行 stop。
 * <p>
 * 停机顺序（严格按设计文档）：
 * <ol>
 *   <li>{@code InstanceRegistry.deregister()} — 从 Redis 注销实例 + 清理用户映射</li>
 *   <li>{@code LocalSessionManager.closeAll()} — 向所有客户端发送 Close Frame（携带重连提示）</li>
 *   <li>等待 {@code schat.shutdown.wait-seconds}（默认 5 秒）— 让客户端完成断开处理</li>
 *   <li>Spring 容器继续关闭，自动触发 MessageStreamConsumer.stop() 和 MessageQueueConsumer.stop()</li>
 * </ol>
 * <p>
 * 注意：本类接管了 deregister 的调用时机，{@code RedisInstanceRegistry.@PreDestroy}
 * 中的 deregister() 会因为已经注销过而产生幂等的 HDEL 操作，不会有副作用。
 */
@Slf4j
@Component
public class GracefulShutdownHandler implements SmartLifecycle {

    private final InstanceRegistry instanceRegistry;
    private final LocalSessionManager localSessionManager;
    private final SchatProperties schatProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GracefulShutdownHandler(InstanceRegistry instanceRegistry,
                                   LocalSessionManager localSessionManager,
                                   SchatProperties schatProperties) {
        this.instanceRegistry = instanceRegistry;
        this.localSessionManager = localSessionManager;
        this.schatProperties = schatProperties;
    }

    @Override
    public void start() {
        running.set(true);
        log.info("GracefulShutdownHandler 已就绪");
    }

    @Override
    public void stop() {
        doShutdown();
    }

    /**
     * Spring 容器关闭时回调此方法（带 Runnable 回调版本）。
     * 执行完停机逻辑后必须调用 {@code callback.run()} 通知容器继续关闭。
     */
    @Override
    public void stop(Runnable callback) {
        doShutdown();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * phase 设为 Integer.MAX_VALUE，确保：
     * - 启动时最后启动（所有依赖组件先就绪）
     * - 关闭时最先关闭（在 MessageStreamConsumer/MessageQueueConsumer 之前）
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void doShutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        int waitSeconds = schatProperties.getShutdown().getWaitSeconds();
        String instanceId = instanceRegistry.getInstanceId();

        log.info("========== 优雅停机开始: instanceId={} ==========", instanceId);

        // 步骤 1：从 Redis 注销实例 + 清理用户映射
        try {
            instanceRegistry.deregister();
            log.info("[停机 1/3] 实例已从 Redis 注销: {}", instanceId);
        } catch (Exception e) {
            log.error("[停机 1/3] 实例注销失败: {}", instanceId, e);
        }

        // 步骤 2：向所有本地连接的用户发送 Close Frame
        try {
            localSessionManager.closeAll("服务正在重启，请稍后自动重连");
            log.info("[停机 2/3] 已向所有本地用户发送关闭帧");
        } catch (Exception e) {
            log.error("[停机 2/3] 发送关闭帧失败", e);
        }

        // 步骤 3：等待客户端完成断开处理
        try {
            log.info("[停机 3/3] 等待 {}s 让客户端完成断开...", waitSeconds);
            Thread.sleep(waitSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[停机 3/3] 等待被中断");
        }

        // 后续：Spring 容器继续关闭，自动触发 MessageStreamConsumer.stop() (phase=10)
        //       和 MessageQueueConsumer.stop() (phase=15)
        log.info("========== 优雅停机完成: instanceId={} ==========", instanceId);
    }
}
