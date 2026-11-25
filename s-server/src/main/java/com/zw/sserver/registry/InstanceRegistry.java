package com.zw.sserver.registry;

/**
 * S_Server 实例注册与发现
 * <p>
 * Redis Key: schat:instances (Hash)
 * Field: instanceId, Value: JSON{instanceId, ip, port, startTime}
 * <p>
 * 辅助 Key: schat:instance:heartbeat:{instanceId} (String)
 * Value: 1, TTL: 30 秒（心跳每 10 秒续期）
 * <p>
 * 【补丁 1：InstanceId 稳定性约束】
 * instanceId 必须使用物理稳定标识（K8s Pod Name 或 内网IP:端口），
 * 禁止使用随机 UUID，确保重启后能重新接管原有 Stream。
 */
public interface InstanceRegistry {

    /** 实例启动时注册，写入 Redis Hash 并设置心跳辅助 Key（TTL 30s） */
    void register();

    /** 每 10 秒心跳续期，刷新辅助心跳 Key 的 TTL */
    void heartbeat();

    /** 实例关闭时主动注销，清理 Hash 和心跳 Key，并清理关联用户映射 */
    void deregister();

    /** 获取当前实例 ID（物理稳定标识，补丁 1） */
    String getInstanceId();

    /** 检查指定实例是否存活（心跳 Key 是否存在） */
    boolean isAlive(String instanceId);
}
