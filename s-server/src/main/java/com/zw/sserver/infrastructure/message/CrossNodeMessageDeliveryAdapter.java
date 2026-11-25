package com.zw.sserver.infrastructure.message;

import com.zw.sserver.domain.message.service.MessageDeliveryPort;
import com.zw.sserver.model.dto.RouteMessage;
import com.zw.sserver.registry.InstanceRegistry;
import com.zw.sserver.router.CrossNodeRouter;
import org.springframework.stereotype.Component;

/**
 * 跨节点实时投递适配器。
 */
@Component
public class CrossNodeMessageDeliveryAdapter implements MessageDeliveryPort {

    private final CrossNodeRouter crossNodeRouter;
    private final InstanceRegistry instanceRegistry;

    public CrossNodeMessageDeliveryAdapter(CrossNodeRouter crossNodeRouter,
                                           InstanceRegistry instanceRegistry) {
        this.crossNodeRouter = crossNodeRouter;
        this.instanceRegistry = instanceRegistry;
    }

    @Override
    public boolean deliverPrivate(Long targetUserId, String payload) {
        RouteMessage routeMessage = new RouteMessage();
        routeMessage.setType("CHAT");
        routeMessage.setTargetUserId(targetUserId);
        routeMessage.setPayload(payload);
        routeMessage.setSourceInstanceId(instanceRegistry.getInstanceId());
        return crossNodeRouter.route(routeMessage);
    }
}
