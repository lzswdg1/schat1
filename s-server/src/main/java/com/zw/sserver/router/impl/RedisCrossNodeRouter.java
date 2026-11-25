package com.zw.sserver.router.impl;

import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.registry.OnlineUserRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import com.zw.sserver.websocket.LocalSessionManager;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Redis Stream 的跨节点消息路由器实现。
 * <p>
 * 路由决策：
 * <ol>
 *   <li>目标用户在本地 {@link LocalSessionManager} → 直接本地投递</li>
 *   <li>目标用户在远程实例 → XADD 到 {@code schat:route:{targetInstanceId}}</li>
 *   <li>目标用户离线（OnlineUserRegistry 无映射） → 返回 false，跳过 Stream 写入</li>
 * </ol>
 * <p>
 * 【补丁 4】使用 {@link StringRedisTemplate} 进行 XADD 操作，避免 Key 乱码。
 */
@Slf4j
@Component
public class RedisCrossNodeRouter implements CrossNodeRouter {

    private static final String STREAM_KEY_PREFIX = "schat:route:";

    private final LocalSessionManager localSessionManager;
    private final OnlineUserRegistry onlineUserRegistry;
    private final InstanceRegistry instanceRegistry;
    private final StringRedisTemplate stringRedisTemplate;

    public RedisCrossNodeRouter(LocalSessionManager localSessionManager,
                                OnlineUserRegistry onlineUserRegistry,
                                InstanceRegistry instanceRegistry,
                                StringRedisTemplate stringRedisTemplate) {
        this.localSessionManager = localSessionManager;
        this.onlineUserRegistry = onlineUserRegistry;
        this.instanceRegistry = instanceRegistry;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean route(RouteMessage message) {
        Long targetUserId = message.getTargetUserId();
        if (targetUserId == null || message.getPayload() == null) return false;
        String targetInstanceId = onlineUserRegistry.getInstanceId(targetUserId);
        if (targetInstanceId == null) return false;

        if (targetInstanceId.equals(instanceRegistry.getInstanceId())) {
            Session session = localSessionManager.getSession(targetUserId);
            if (session == null) return false;
            String connectionId = (String) session.getUserProperties().get("connectionId");
            return connectionId != null && onlineUserRegistry.isCurrentConnection(
                    targetUserId, targetInstanceId, connectionId)
                    && localSessionManager.sendToUser(targetUserId, message.getPayload());
        }
        if (!instanceRegistry.isAlive(targetInstanceId)) {
            return false;
        }

        // 4. Target is on a remote instance → XADD to schat:route:{targetInstanceId}
        try {
            String streamKey = STREAM_KEY_PREFIX + targetInstanceId;

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("type", message.getType());
            fields.put("targetUserId", targetUserId.toString());
            fields.put("payload", message.getPayload());
            fields.put("sourceInstanceId", message.getSourceInstanceId());

            StringRecord record = StreamRecords.string(fields).withStreamKey(streamKey);
            if (stringRedisTemplate.opsForStream().add(record) == null) return false;

            log.debug("远程路由成功: targetUserId={}, targetInstanceId={}, streamKey={}",
                    targetUserId, targetInstanceId, streamKey);
            return true;
        } catch (Exception e) {
            log.error("Stream 写入失败: targetUserId={}, targetInstanceId={}",
                    targetUserId, targetInstanceId, e);
            return false;
        }
    }
}
