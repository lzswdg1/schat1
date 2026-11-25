package com.zw.suser.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginRateLimitInterceptorTest {

    @Test
    void rotatingUntrustedHeadersNeverChangesTheRateLimitIdentity() throws Exception {
        LoginRateLimiter limiter = mock(LoginRateLimiter.class);
        when(limiter.tryAcquire("192.0.2.10")).thenReturn(true, true, true, true, true, false);
        var interceptor = new LoginRateLimitInterceptor(limiter, new ObjectMapper(), new LoginClientIpResolver(""));
        for (int attempt = 0; attempt < 6; attempt++) {
            var request = new MockHttpServletRequest("POST", "/auth/login");
            request.setRemoteAddr("192.0.2.10");
            request.addHeader("X-Forwarded-For", "198.51.100." + attempt);
            request.addHeader("X-Real-IP", "203.0.113." + attempt);
            var response = new MockHttpServletResponse();
            assertEquals(attempt < 5, interceptor.preHandle(request, response, new Object()));
            if (attempt == 5) assertEquals(429, response.getStatus());
        }
        verify(limiter, times(6)).tryAcquire("192.0.2.10");
        verifyNoMoreInteractions(limiter);
    }

    @Test
    void redisFailureReturnsServiceUnavailableWithoutCallingTheController() throws Exception {
        LoginRateLimiter limiter = mock(LoginRateLimiter.class);
        when(limiter.tryAcquire("192.0.2.10")).thenThrow(new RedisConnectionFailureException("offline"));
        var interceptor = new LoginRateLimitInterceptor(limiter, new ObjectMapper(), new LoginClientIpResolver(""));
        var request = new MockHttpServletRequest("POST", "/auth/register");
        request.setRemoteAddr("192.0.2.10");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("限流服务暂不可用"));
    }
}
