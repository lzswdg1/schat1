package com.zw.suser.feign;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 请求拦截器：自动为调用 s-server 内部接口的请求添加 X-Internal-Token。
 */
@Configuration
public class InternalFeignConfig {

    @Value("${schat.internal.secret}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalTokenInterceptor() {
        return template -> {
            if (template.url().startsWith("/internal/")) {
                template.header("X-Internal-Token", internalToken);
            }
        };
    }
}
