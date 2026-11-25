package com.zw.sserver.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public class DeliveryOpsAccessInterceptor implements HandlerInterceptor {

    public static final String ACTOR = "deliveryOpsActor";
    public static final String REQUEST_ID = "deliveryOpsRequestId";
    private final byte[] secret;

    public DeliveryOpsAccessInterceptor(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String supplied = request.getHeader("X-SChat-Ops-Token");
        if (supplied == null || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"运维认证失败\",\"data\":null}");
            return false;
        }
        String actor = request.getHeader("X-SChat-Ops-Actor");
        if (actor == null || !actor.matches("[A-Za-z0-9_.:@-]{1,64}")) {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"缺少或无效的操作人标签\",\"data\":null}");
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ACTOR, actor);
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-SChat-Ops-Request-Id", requestId);
        return true;
    }
}
