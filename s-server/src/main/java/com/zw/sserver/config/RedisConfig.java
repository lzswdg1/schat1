package com.zw.sserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置
 * <p>
 * 【补丁 4】禁止使用默认 JDK 序列化，所有 Key 必须为人类可读的纯文本字符串。
 * <ul>
 *   <li>Key / HashKey → {@link StringRedisSerializer}</li>
 *   <li>Value / HashValue → {@link GenericJackson2JsonRedisSerializer}</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 通用 RedisTemplate，用于 Hash、ZSet 等结构的读写。
     * Key 和 HashKey 使用 StringRedisSerializer，确保 Redis 中存储的 Key 为纯文本。
     * Value 和 HashValue 使用 GenericJackson2JsonRedisSerializer，支持 JSON 序列化。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate，用于 Redis Stream 操作。
     * Stream 的 field/value 均为字符串，使用 StringRedisTemplate 即可。
     * Spring Boot 会自动配置一个，此处显式声明以确保可用。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
