package com.zw.sserver.config;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 配置
 * <p>
 * 【消息体大小限制 — 第一层防线】
 * 通过 ServerContainer 设置 WebSocket 帧的最大文本消息大小。
 * 超过限制的消息在 Tomcat 层就被拒绝，不会进入 onMessage。
 * <p>
 * 这是"纵深防御"的第一层：
 *   第 1 层：Tomcat WebSocket 容器限制（本类，64KB）
 *   第 2 层：业务层 content 字段长度校验（ChatEndpoint.onMessage，5000 字符）
 * <p>
 * 为什么要两层？
 *   - 第 1 层防的是"超大帧攻击"（比如发一个 10MB 的 WebSocket 帧，直接打爆内存）
 *   - 第 2 层防的是"合法大小但内容过长"（比如 60KB 的 JSON 里 content 字段有 50KB）
 */
@Configuration
public class WebSocketConfig {

    /**
     * 注入 ServerEndpointExporter
     * 作用：自动注册所有打上了 @ServerEndpoint 注解的 Bean
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    /**
     * 设置 WebSocket 最大消息大小限制
     * <p>
     * 【为什么是 64KB？】
     * IM 场景下，一条文本消息 5000 字 ≈ 15KB（UTF-8），
     * 加上 JSON 信封（fromId、toId、msgType 等字段）≈ 16KB。
     * 图片消息存的是 URL（不是 Base64），也就几百字节。
     * 64KB 足够覆盖所有正常场景，同时拦截恶意超大消息。
     * <p>
     * 【Tomcat 默认值是 8KB】
     * 如果不设置，Tomcat 默认 maxTextMessageBufferSize = 8192 字节，
     * 对于带引用回复的消息可能不够用，所以放宽到 64KB。
     */
    @Bean
    public ServletContextInitializer webSocketLimitInitializer() {
        return servletContext -> {
            ServerContainer container = (ServerContainer) servletContext.getAttribute(
                    "jakarta.websocket.server.ServerContainer");
            if (container != null) {
                container.setDefaultMaxTextMessageBufferSize(64 * 1024);   // 64KB
                container.setDefaultMaxBinaryMessageBufferSize(64 * 1024); // 64KB
            }
        };
    }
}