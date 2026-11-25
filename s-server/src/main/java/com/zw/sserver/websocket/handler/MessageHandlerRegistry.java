package com.zw.sserver.websocket.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息处理器注册中心
 * <p>
 * 【核心机制：Spring 自动收集 + Map 路由】
 * <p>
 * Spring 会自动把所有实现了 {@link MessageHandler} 接口的 Bean 注入到构造器的 List 参数中。
 * 这是 Spring 的"集合注入"特性：
 * <pre>
 *   @Component
 *   public class HeartbeatHandler implements MessageHandler { ... }
 *
 *   @Component
 *   public class ChatMessageHandler implements MessageHandler { ... }
 *
 *   // Spring 自动收集所有 MessageHandler 实现，注入到这个 List 里
 *   public MessageHandlerRegistry(List&lt;MessageHandler&gt; handlers) { ... }
 * </pre>
 * <p>
 * 这样做的好处：
 *   1. 新增消息类型只需要加一个 @Component 类，不用改任何已有代码（开闭原则）
 *   2. 不需要手动 new 或手动注册，Spring 自动发现
 *   3. 路由查找是 O(1) 的 HashMap.get，不是 if-else 链
 */
@Component
public class MessageHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(MessageHandlerRegistry.class);

    /**
     * msgType → Handler 的映射表
     * key 统一转大写，避免大小写不一致的问题
     */
    private final Map<String, MessageHandler> handlerMap = new HashMap<>();

    /**
     * 构造器注入：Spring 自动收集所有 MessageHandler 实现
     * <p>
     * 【Java 基础：构造器注入 vs @Autowired 字段注入】
     * 构造器注入是 Spring 官方推荐的方式：
     *   - 依赖不可变（final）
     *   - 不会出现 NPE（对象创建时依赖就已经就绪）
     *   - 方便单元测试（直接 new 传参，不需要 Spring 容器）
     */
    public MessageHandlerRegistry(List<MessageHandler> handlers) {
        for (MessageHandler handler : handlers) {
            String type = handler.getMsgType().toUpperCase();
            MessageHandler existing = handlerMap.put(type, handler);
            if (existing != null) {
                log.warn("消息类型 {} 存在重复 Handler：{} 被 {} 覆盖",
                        type, existing.getClass().getSimpleName(), handler.getClass().getSimpleName());
            }
            log.info("注册消息处理器: {} → {}", type, handler.getClass().getSimpleName());
        }
    }

    /**
     * 根据 msgType 查找对应的 Handler
     *
     * @param msgType 消息类型（不区分大小写）
     * @return 对应的 Handler，找不到返回 null
     */
    public MessageHandler getHandler(String msgType) {
        if (msgType == null) return null;
        return handlerMap.get(msgType.toUpperCase());
    }
}
