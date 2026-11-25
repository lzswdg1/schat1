package com.zw.sserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeliveryAlertProperties.class)
public class DeliveryAlertConfiguration {

    public DeliveryAlertConfiguration(DeliveryAlertProperties properties, DeliveryOpsProperties operations,
                                      @Value("${schat.internal.secret:}") String internalSecret,
                                      @Value("${sa-token.jwt-secret-key:}") String jwtSecret) {
        if (properties.isEnabled() && (properties.getSecret().equals(internalSecret)
                || properties.getSecret().equals(jwtSecret) || properties.getSecret().equals(operations.getSecret()))) {
            throw new IllegalStateException("投递告警签名密钥不能复用内部调用、JWT 或运维密钥");
        }
    }
}
