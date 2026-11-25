package com.zw.suser.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_REDIS_PORT", matches = ".+")
class LoginRateLimiterIntegrationTest {

    private static LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private final String key = "schat:rate:login:192.0.2.10";

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("SCHAT_TEST_REDIS_PORT")));
        connections.afterPropertiesSet();
        connections.start();
    }

    @BeforeEach
    void reset() {
        redis = new StringRedisTemplate(connections);
        redis.delete(key);
    }

    @AfterEach
    void cleanup() {
        redis.delete(key);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Test
    void loginAndRegistrationAcrossInstancesCannotResetAnExhaustedIpBudgetWithSpoofedHeaders() throws Exception {
        var first = new LoginRateLimitInterceptor(new LoginRateLimiter(redis), new ObjectMapper(), new LoginClientIpResolver(""));
        var second = new LoginRateLimitInterceptor(new LoginRateLimiter(new StringRedisTemplate(connections)),
                new ObjectMapper(), new LoginClientIpResolver(""));
        assertTrue(first.preHandle(request("/auth/login", "198.51.100.1"), new MockHttpServletResponse(), new Object()));
        assertTrue(second.preHandle(request("/auth/register", "198.51.100.2"), new MockHttpServletResponse(), new Object()));
        assertTrue(redis.getExpire(key) > 0);
        redis.opsForHash().put(key, "tokens", "0");
        redis.opsForHash().put(key, "updated", String.valueOf(System.currentTimeMillis() + 60000));
        for (int attempt = 0; attempt < 20; attempt++) {
            var response = new MockHttpServletResponse();
            assertFalse((attempt % 2 == 0 ? first : second).preHandle(
                    request("/auth/login", "198.51.100." + attempt), response, new Object()));
            assertEquals(429, response.getStatus());
        }
    }

    private MockHttpServletRequest request(String path, String forwarded) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", forwarded);
        request.addHeader("X-Real-IP", forwarded);
        return request;
    }
}
