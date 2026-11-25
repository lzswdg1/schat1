package com.zw.suser.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zw.scommon.api.CommonResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录/注册接口限流拦截器。
 * <p>
 * 拦截 /auth/login 和 /auth/register，基于客户端 IP 进行令牌桶限流，
 * 防止暴力破解密码和批量注册攻击。
 */
@Component
public class LoginRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitInterceptor.class);

    private final LoginRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final LoginClientIpResolver ipResolver;

    public LoginRateLimitInterceptor(LoginRateLimiter rateLimiter, ObjectMapper objectMapper,
                                     LoginClientIpResolver ipResolver) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.ipResolver = ipResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = ipResolver.resolve(request);
        boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(clientIp);
        } catch (DataAccessException exception) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    CommonResult.failed("限流服务暂不可用，请稍后再试")));
            return false;
        }

        if (!allowed) {
            log.warn("[登录限流] IP {} 请求过于频繁，已被限流，URI: {}", clientIp, request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    CommonResult.failed("请求过于频繁，请稍后再试")));
            return false;
        }
        return true;
    }

}
