package com.zw.sserver.router;

import com.zw.sserver.model.dto.RouteMessage;

/**
 * 跨节点消息路由器
 * <p>
 * 决策逻辑：本地优先，远程走 Redis Stream，离线跳过。
 * <ol>
 *   <li>目标在本地 → {@code LocalSessionManager} 直接投递</li>
 *   <li>目标在远程 → XADD 到目标实例的 MessageStream</li>
 *   <li>目标离线 → 跳过路由（仅持久化）</li>
 * </ol>
 */
public interface CrossNodeRouter {

    /**
     * 路由消息到目标用户。
     *
     * @param message 路由消息信封
     * @return true 表示消息已投递（本地或远程），false 表示用户离线
     */
    boolean route(RouteMessage message);
}
