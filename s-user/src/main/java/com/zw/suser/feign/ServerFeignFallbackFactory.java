package com.zw.suser.feign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * ServerFeignClient 熔断降级工厂。
 * <p>
 * 当 s-server 不可用时（宕机、超时、异常），Feign 调用不会直接抛异常，
 * 而是走降级逻辑，保证 s-user 的核心业务（好友申请/处理/删除）不被拖垮。
 * <p>
 * 使用 FallbackFactory 而非 Fallback，可以拿到具体的异常信息用于日志排查。
 */
@Slf4j
@Component
public class ServerFeignFallbackFactory implements FallbackFactory<ServerFeignClient> {

    @Override
    public ServerFeignClient create(Throwable cause) {
        return new ServerFeignClient() {

            @Override
            public String sendMessage(Long userId, String message) {
                log.error("[Feign 熔断] 推送通知失败，s-server 不可用，目标用户: {}，原因: {}",
                        userId, cause.getMessage());
                // 降级策略：通知推送失败不影响主流程，返回 FALLBACK 标记
                return "FALLBACK";
            }

            @Override
            public String deleteMessages(Long userId1, Long userId2) {
                log.error("[Feign 熔断] 删除聊天记录失败，s-server 不可用，用户: {} 和 {}，原因: {}",
                        userId1, userId2, cause.getMessage());
                // 降级策略：聊天记录删除失败不阻塞好友删除主流程
                return "FALLBACK";
            }
        };
    }
}
