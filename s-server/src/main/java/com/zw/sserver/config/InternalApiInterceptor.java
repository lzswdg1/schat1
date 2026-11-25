package com.zw.sserver.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部接口安全拦截器。
 * <p>
 * 校验请求头 X-Internal-Token，防止外部用户直接调用 /internal/** 接口。
 * s-user 通过 Feign 调用时需携带此 Header。
 */
@Component
public class InternalApiInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalApiInterceptor.class);

    private static final String HEADER_NAME = "X-Internal-Token";

    @Value("${schat.internal.secret}")
    private String internalSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader(HEADER_NAME);
        if (internalSecret.equals(token)) {
            return true;
        }
        log.warn("[内部接口拦截] 非法访问 {}，缺少或错误的 X-Internal-Token", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"禁止访问内部接口\",\"data\":null}");
        return false;
    }
}
