package com.zw.sserver.model.dto;

import lombok.Data;

/**
 * 跨节点路由消息信封
 * <p>
 * type 取值：
 * <ul>
 *   <li>CHAT — 聊天消息</li>
 *   <li>READ — 已读回执</li>
 *   <li>NOTICE — 系统通知</li>
 *   <li>KICK_SESSION — 踢掉旧会话（多端顶号）</li>
 * </ul>
 */
@Data
public class RouteMessage {

    /** 消息类型：CHAT / READ / NOTICE / KICK_SESSION */
    private String type;

    /** 目标用户 ID */
    private Long targetUserId;

    /** 消息载荷（JSON 字符串，具体内容由 type 决定） */
    private String payload;

    /** 来源实例 ID（用于调试和追踪） */
    private String sourceInstanceId;
}
