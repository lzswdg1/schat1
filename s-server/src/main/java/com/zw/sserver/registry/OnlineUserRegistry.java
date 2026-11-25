package com.zw.sserver.registry;

/**
 * 分布式在线用户注册表
 * <p>
 * Redis Key: schat:online:users (Hash)
 * Field: userId, Value: instanceId|connectionId
 */
public interface OnlineUserRegistry {

    /** 用户上线，记录实例与连接身份 */
    void userOnline(Long userId, String instanceId, String connectionId);

    /** 用户下线，仅删除仍属于该连接的映射 */
    void userOffline(Long userId, String instanceId, String connectionId);

    boolean isCurrentConnection(Long userId, String instanceId, String connectionId);

    /** 查询用户所在实例 ID，返回 null 表示离线 */
    String getInstanceId(Long userId);

    /** 清理指定实例的所有用户映射（实例宕机时调用） */
    void cleanByInstance(String instanceId);
}
