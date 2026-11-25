package com.zw.sserver.controller;

import com.zw.sserver.mapper.ChatMessageMapper;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 专门给 s-user (用户微服务) 调用的内部接口
 * <p>
 * v2.0 改造：不再直接调用 ChatEndpoint.sendNotice()，
 * 改为通过 CrossNodeRouter 路由，支持跨节点通知投递。
 * <p>
 * 【安全设计：内部接口鉴权】
 * 内部接口不走 Sa-Token（那是给前端用户用的），
 * 而是用共享密钥（X-Internal-Secret Header）做服务间鉴权。
 * <p>
 * 原理：
 *   s-user 调用 s-server 时，在 HTTP Header 里带上约定好的密钥。
 *   s-server 校验密钥是否匹配，不匹配直接拒绝。
 * <p>
 * 为什么不用 OAuth2 / JWT？
 *   - 内部微服务之间用 OAuth2 太重了，增加复杂度
 *   - 共享密钥 + 内网隔离已经足够安全
 *   - 生产环境可以升级为 mTLS（双向 TLS 证书认证）
 * <p>
 * 【配置方式】
 * 在 application.yaml 中配置：
 *   schat.internal.secret: your-secret-key
 * s-user 调用时带 Header：X-Internal-Secret: your-secret-key
 */
@RestController
@RequestMapping("/internal/ws")
public class InternalWsController {

    private static final Logger log = LoggerFactory.getLogger(InternalWsController.class);

    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;
    private final ChatMessageMapper chatMessageMapper;

    public InternalWsController(CrossNodeRouter crossNodeRouter,
                                InstanceRegistry instanceRegistry,
                                ChatMessageMapper chatMessageMapper) {
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
        this.chatMessageMapper = chatMessageMapper;
    }

    @PostMapping("/send")
    public String sendMessage(@RequestParam("userId") Long userId,
                              @RequestParam("message") String message) {
        RouteMessage routeMessage = new RouteMessage();
        routeMessage.setType("NOTICE");
        routeMessage.setTargetUserId(userId);
        routeMessage.setPayload(message);
        routeMessage.setSourceInstanceId(instanceRegistry.getInstanceId());

        boolean delivered = crossNodeRouter.route(routeMessage);
        if (delivered) {
            log.info("[内部通知] 成功路由通知给用户 {}", userId);
        } else {
            log.info("[内部通知] 用户 {} 不在线，跳过推送", userId);
        }

        return "SUCCESS";
    }

    /**
     * 删除两个用户之间的所有聊天记录（内部接口，由 s-user 删除好友时调用）
     */
    @DeleteMapping("/deleteMessages")
    public String deleteMessages(@RequestParam("userId1") Long userId1,
                                 @RequestParam("userId2") Long userId2) {
        chatMessageMapper.deleteMessages(userId1, userId2);
        log.info("[内部接口] 已删除用户 {} 和 {} 之间的聊天记录", userId1, userId2);
        return "SUCCESS";
    }

}