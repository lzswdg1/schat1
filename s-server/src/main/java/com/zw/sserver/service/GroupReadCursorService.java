package com.zw.sserver.service;

import lombok.extern.slf4j.Slf4j;
import com.zw.sserver.mapper.GroupMessageMapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.model.entity.GroupMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 群聊已读游标 + 未读计数服务
 * <p>
 * MySQL tb_group_member.last_read_seq 是已读游标的事实来源；Redis Hash 仅作镜像。
 * <p>
 * 【读扩散模型】
 * 群消息只存一份到 tb_group_message，每个用户维护自己的已读游标。
 * 未读数从群消息表按 seq 统计，因此 Redis 丢失或服务重启后可以自动恢复。
 */
@Slf4j
@Service
public class GroupReadCursorService {

    private static final String CURSOR_KEY_PREFIX = "schat:group:cursor:v2:";
    private static final String LEGACY_CURSOR_KEY_PREFIX = "schat:group:cursor:";

    private final StringRedisTemplate redisTemplate;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final DefaultRedisScript<Long> advanceCursorScript;

    public GroupReadCursorService(StringRedisTemplate redisTemplate,
                                  GroupMessageMapper groupMessageMapper,
                                  GroupMemberMapper groupMemberMapper) {
        this.redisTemplate = redisTemplate;
        this.groupMessageMapper = groupMessageMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.advanceCursorScript = new DefaultRedisScript<>();
        this.advanceCursorScript.setResultType(Long.class);
        this.advanceCursorScript.setScriptText("""
                local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
                local requested = tonumber(ARGV[2])
                if requested > current then
                    redis.call('HSET', KEYS[1], ARGV[1], requested)
                    return 1
                end
                return 0
                """);
    }

    /**
     * 获取用户在某个群的已读游标
     */
    public Long getCursor(Long userId, Long groupId) {
        Long durableCursor = groupMemberMapper.selectLastReadSeq(groupId, userId);
        long cursor = durableCursor != null ? durableCursor : 0L;
        if (cursor == 0L) {
            cursor = migrateCachedCursor(userId, groupId);
            if (cursor > 0L) {
                groupMemberMapper.advanceLastReadSeq(groupId, userId, cursor);
            }
        }
        try {
            redisTemplate.opsForHash().put(
                    CURSOR_KEY_PREFIX + userId,
                    groupId.toString(),
                    String.valueOf(cursor));
        } catch (Exception e) {
            log.debug("群已读游标 Redis 镜像失败，继续使用 MySQL: userId={}, groupId={}", userId, groupId);
        }
        return cursor;
    }

    private long migrateCachedCursor(Long userId, Long groupId) {
        try {
            Object cachedSeq = redisTemplate.opsForHash().get(
                    CURSOR_KEY_PREFIX + userId, groupId.toString());
            if (cachedSeq != null) {
                return Long.parseLong(cachedSeq.toString());
            }

            Object legacyMessageId = redisTemplate.opsForHash().get(
                    LEGACY_CURSOR_KEY_PREFIX + userId, groupId.toString());
            if (legacyMessageId == null) return 0L;
            GroupMessage legacyMessage = groupMessageMapper.selectById(
                    Long.parseLong(legacyMessageId.toString()));
            if (legacyMessage != null
                    && groupId.equals(legacyMessage.getGroupId())
                    && legacyMessage.getSeq() != null) {
                return legacyMessage.getSeq();
            }
        } catch (Exception e) {
            log.warn("迁移旧群已读游标失败: userId={}, groupId={}", userId, groupId, e);
        }
        return 0L;
    }

    /**
     * 按消息 ID 推进群已读游标。服务端先解析消息所属群和 seq，绝不信任
     * 客户端直接提供的游标；Lua 保证游标只能前进，迟到/重复回执不会误清未读。
     */
    public boolean updateCursor(Long userId, Long groupId, Long messageId) {
        GroupMessage message = groupMessageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getGroupId()) || message.getSeq() == null) {
            log.warn("忽略无效群已读回执: userId={}, groupId={}, messageId={}", userId, groupId, messageId);
            return false;
        }

        int advanced = groupMemberMapper.advanceLastReadSeq(groupId, userId, message.getSeq());
        if (advanced <= 0) {
            return false;
        }

        try {
            redisTemplate.execute(
                    advanceCursorScript,
                    List.of(CURSOR_KEY_PREFIX + userId),
                    groupId.toString(),
                    message.getSeq().toString());
        } catch (Exception e) {
            log.debug("群已读游标 Redis 镜像失败，MySQL 已推进: userId={}, groupId={}, seq={}",
                    userId, groupId, message.getSeq());
        }
        return true;
    }

    /**
     * 获取用户在某个群的未读消息数
     */
    public int getUnreadCount(Long userId, Long groupId) {
        long unread = groupMessageMapper.countAfterSeq(groupId, userId, getCursor(userId, groupId));
        return unread > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) unread;
    }

    /**
     * 获取用户所有群的未读分布
     */
    public Map<Long, Integer> getAllUnreadCounts(Long userId, Set<Long> groupIds) {
        Map<Long, Integer> result = new HashMap<>();
        for (Long groupId : groupIds) {
            int count = getUnreadCount(userId, groupId);
            if (count > 0) {
                result.put(groupId, count);
            }
        }
        return result;
    }
}
