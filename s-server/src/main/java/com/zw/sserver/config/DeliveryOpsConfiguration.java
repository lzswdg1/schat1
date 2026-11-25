package com.zw.sserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(DeliveryOpsProperties.class)
public class DeliveryOpsConfiguration implements WebMvcConfigurer {

    private final DeliveryOpsProperties properties;

    public DeliveryOpsConfiguration(DeliveryOpsProperties properties,
                                     @Value("${schat.internal.secret:}") String internalSecret,
                                     @Value("${sa-token.jwt-secret-key:}") String jwtSecret) {
        this.properties = properties;
        if (properties.isEnabled() && (properties.getSecret().equals(internalSecret) || properties.getSecret().equals(jwtSecret))) {
            throw new IllegalStateException("运维密钥不能复用内部调用密钥或 JWT 密钥");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.isEnabled()) {
            registry.addInterceptor(new DeliveryOpsAccessInterceptor(properties.getSecret())).addPathPatterns("/ops/**");
        }
    }
}
