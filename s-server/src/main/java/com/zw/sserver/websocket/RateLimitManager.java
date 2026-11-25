package com.zw.sserver.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户级消息限流器 — 令牌桶算法（Token Bucket）
 * <p>
 * 【为什么需要限流？】
 * 没有限流的 IM 系统 = 裸奔：
 *   - 恶意用户写脚本每秒发 1000 条消息 → Redis XADD 被打爆
 *   - 一个用户刷屏 → 影响同一 EventLoop/线程上的其他用户
 *   - 消息队列堆积 → MySQL 写入延迟飙升 → 整个系统雪崩
 * <p>
 * 【令牌桶算法原理】
 * 想象一个桶，以固定速率往里放令牌（比如每秒放 10 个）。
 * 每次发消息需要取一个令牌：
 *   - 桶里有令牌 → 取走一个，允许发送
 *   - 桶里没令牌 → 拒绝发送
 *   - 桶有容量上限 → 允许短时间突发（比如攒了 5 个令牌，可以一口气发 5 条）
 * <p>
 * 和"滑动窗口"的区别：
 *   令牌桶：允许突发流量（桶里攒了令牌就能用），更适合 IM 场景
 *   滑动窗口：严格限制每个时间窗口的请求数，更适合 API 限流
 * <p>
 * 【为什么不用 Guava RateLimiter？】
 *   1. Guava RateLimiter 是阻塞式的（acquire 会 sleep 等待），不适合 WebSocket 场景
 *   2. 手写更轻量，不需要引入整个 Guava 依赖
 * <p>
 * 【线程安全】
 * 使用 ConcurrentHashMap 存储每个用户的桶。
 * TokenBucket 内部用 synchronized 保证单个用户的并发安全。
 * 不同用户之间互不影响（各自有独立的桶）。
 */
@Component
public class RateLimitManager {

    private static final Logger log = LoggerFactory.getLogger(RateLimitManager.class);

    /** 每秒允许的最大消息数 */
    private static final double PERMITS_PER_SECOND = 10.0;

    /** 桶容量（允许的最大突发量） */
    private static final double MAX_PERMITS = 20.0;

    /**
     * userId → TokenBucket 映射
     * <p>
     * 【为什么用 ConcurrentHashMap？】
     * 多个线程可能同时为不同用户创建桶（onMessage 在 Tomcat 线程池中并发执行）。
     * ConcurrentHashMap.computeIfAbsent 是原子操作，保证每个用户只创建一个桶。
     */
    private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TokenBucket> receiptBuckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取一个令牌（非阻塞）
     *
     * @param userId 用户 ID
     * @return true = 允许发送，false = 被限流
     */
    public boolean tryAcquire(Long userId) {
        TokenBucket bucket = buckets.computeIfAbsent(userId, id -> new TokenBucket(PERMITS_PER_SECOND, MAX_PERMITS));
        return bucket.tryAcquire();
    }

    public boolean tryAcquireReceipt(Long userId) {
        return receiptBuckets.computeIfAbsent(userId, id -> new TokenBucket(200.0, 500.0)).tryAcquire();
    }

    /**
     * 用户下线时清理桶，防止内存泄漏
     * <p>
     * 如果不清理，长时间运行后 ConcurrentHashMap 会积累大量已下线用户的桶。
     * 虽然每个桶很小（几十字节），但百万用户级别就是几十 MB 的无用内存。
     */
    public void remove(Long userId) {
        buckets.remove(userId);
        receiptBuckets.remove(userId);
    }

    /**
     * 令牌桶实现
     * <p>
     * 【核心思路：懒计算】
     * 不需要真的有一个定时器每秒往桶里放令牌。
     * 而是在每次 tryAcquire 时，根据"距离上次的时间差"计算应该补充多少令牌。
     * <p>
     * 比如：
     *   上次取令牌是 0.5 秒前，速率是 10/秒
     *   → 应该补充 0.5 × 10 = 5 个令牌
     *   → 加上桶里剩余的，但不超过 maxPermits
     * <p>
     * 这种"懒计算"方式：
     *   - 不需要后台线程
     *   - 不需要定时器
     *   - 只在需要时才计算
     *   - 时间复杂度 O(1)
     */
    static class TokenBucket {

        private final double permitsPerSecond;  // 每秒生成的令牌数
        private final double maxPermits;        // 桶的最大容量
        private double currentPermits;          // 当前可用令牌数
        private long lastRefillTime;            // 上次补充令牌的时间（纳秒）

        TokenBucket(double permitsPerSecond, double maxPermits) {
            this.permitsPerSecond = permitsPerSecond;
            this.maxPermits = maxPermits;
            this.currentPermits = maxPermits;   // 初始满桶
            this.lastRefillTime = System.nanoTime();
        }

        /**
         * 尝试取一个令牌
         * <p>
         * 【synchronized 的范围】
         * 锁的是单个 TokenBucket 实例（即单个用户的桶）。
         * 不同用户的 tryAcquire 互不阻塞。
         * 同一用户的并发请求会串行化，但令牌桶操作是纳秒级的，不会成为瓶颈。
         */
        synchronized boolean tryAcquire() {
            refill();
            if (currentPermits >= 1.0) {
                currentPermits -= 1.0;
                return true;
            }
            return false;
        }

        /**
         * 补充令牌（懒计算）
         * <p>
         * 【计算公式】
         * elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0
         * newPermits = elapsedSeconds × permitsPerSecond
         * currentPermits = min(currentPermits + newPermits, maxPermits)
         * <p>
         * 【为什么用 nanoTime 而不是 currentTimeMillis？】
         * System.nanoTime() 是单调递增的，不受系统时钟调整影响。
         * System.currentTimeMillis() 可能因为 NTP 同步而跳变（往前或往后）。
         * 对于计算时间差，nanoTime 更可靠。
         */
        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                double newPermits = elapsedSeconds * permitsPerSecond;
                currentPermits = Math.min(currentPermits + newPermits, maxPermits);
                lastRefillTime = now;
            }
        }
    }
}
