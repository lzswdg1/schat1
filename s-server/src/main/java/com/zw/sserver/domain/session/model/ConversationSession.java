package com.zw.sserver.domain.session.model;

/**
 * 会话领域对象。
 */
public class ConversationSession {

    private final String id;
    private final SessionType type;
    private final Long targetId;
    private final String targetName;
    private final String targetAvatar;
    private final String lastMessage;
    private final Long lastTime;
    private final Integer unreadCount;

    private ConversationSession(String id,
                                SessionType type,
                                Long targetId,
                                String targetName,
                                String targetAvatar,
                                String lastMessage,
                                Long lastTime,
                                Integer unreadCount) {
        this.id = id;
        this.type = type;
        this.targetId = targetId;
        this.targetName = targetName;
        this.targetAvatar = targetAvatar;
        this.lastMessage = lastMessage;
        this.lastTime = lastTime;
        this.unreadCount = unreadCount != null ? unreadCount : 0;
    }

    public static ConversationSession privateSession(Long peerId,
                                                     String peerName,
                                                     String peerAvatar,
                                                     String lastMessage,
                                                     Long lastTime,
                                                     Integer unreadCount) {
        return new ConversationSession(
                String.valueOf(peerId),
                SessionType.PRIVATE,
                peerId,
                peerName,
                peerAvatar,
                lastMessage,
                lastTime,
                unreadCount
        );
    }

    public static ConversationSession groupSession(Long groupId,
                                                   String groupName,
                                                   String groupAvatar,
                                                   String lastMessage,
                                                   Long lastTime,
                                                   Integer unreadCount) {
        return new ConversationSession(
                "group_" + groupId,
                SessionType.GROUP,
                groupId,
                groupName,
                groupAvatar,
                lastMessage,
                lastTime,
                unreadCount
        );
    }

    public String getId() {
        return id;
    }

    public SessionType getType() {
        return type;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTargetAvatar() {
        return targetAvatar;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public Long getLastTime() {
        return lastTime;
    }

    public Integer getUnreadCount() {
        return unreadCount;
    }
}
