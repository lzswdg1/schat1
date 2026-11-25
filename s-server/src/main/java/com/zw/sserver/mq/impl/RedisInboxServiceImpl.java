package com.zw.sserver.mq.impl;

import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.InboxMessage;
import com.zw.sserver.mq.RedisInboxService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis ZSet 的用户收件箱实现。
 * <p>
 * Key 格式: {@code schat:inbox:{userId}}
 * Member 格式: {@code {messageId}:{briefContent}}
 * Score: 消息时间戳（毫秒）
 * <p>
 * 【补丁 3：ZSet TTL 刷新风暴优化】
 * addToInbox() 使用 Lua 脚本实现 ZADD + 条件性 EXPIRE 原子操作：
 * 仅当 Key 首次创建时设置 TTL（7 天），后续 ZADD 不刷新 TTL。
 * <p>
 * 【补丁 4】使用 {@link StringRedisTemplate} 进行操作，避免 Key 乱码。
 */
@Slf4j
@Component
public class RedisInboxServiceImpl implements RedisInboxService {

    private static final String INBOX_KEY_PREFIX = "schat:inbox:";

    /**
     * Lua 脚本：原子执行 ZADD + 条件性 EXPIRE
     * <ul>
     *   <li>KEYS[1] = inbox key</li>
     *   <li>ARGV[1] = score (timestamp in millis)</li>
     *   <li>ARGV[2] = member ({messageId}:{briefContent})</li>
     *   <li>ARGV[3] = TTL (seconds)</li>
     * </ul>
     */
    private static final String ADD_TO_INBOX_LUA =
            "local key = KEYS[1]\n" +
            "local existed = redis.call('EXISTS', key)\n" +
            "local added = redis.call('ZADD', key, ARGV[1], ARGV[2])\n" +
            "if existed == 0 then\n" +
            "    redis.call('EXPIRE', key, ARGV[3])\n" +
            "end\n" +
            "return added";

    /**
     * Lua 脚本：按 member 前缀匹配删除 ZSet 中的元素。
     * 使用 ZSCAN 避免全量拉取，找到匹配项后 ZREM 删除。
     * <ul>
     *   <li>KEYS[1] = inbox key</li>
     *   <li>ARGV[1] = member prefix ({messageId}:)</li>
     * </ul>
     */
    private static final String REMOVE_BY_PREFIX_LUA =
            "local cursor = '0'\n" +
            "repeat\n" +
            "    local result = redis.call('ZSCAN', KEYS[1], cursor, 'MATCH', ARGV[1] .. '*', 'COUNT', 100)\n" +
            "    cursor = result[1]\n" +
            "    local members = result[2]\n" +
            "    for i = 1, #members, 2 do\n" +
            "        redis.call('ZREM', KEYS[1], members[i])\n" +
            "        return 1\n" +
            "    end\n" +
            "until cursor == '0'\n" +
            "return 0";

    private final DefaultRedisScript<Long> removeByPrefixScript;
    private final DefaultRedisScript<Long> addToInboxScript;
    private final StringRedisTemplate stringRedisTemplate;
    private final SchatProperties schatProperties;

    public RedisInboxServiceImpl(StringRedisTemplate stringRedisTemplate,
                                 SchatProperties schatProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.schatProperties = schatProperties;

        this.addToInboxScript = new DefaultRedisScript<>();
        this.addToInboxScript.setScriptText(ADD_TO_INBOX_LUA);
        this.addToInboxScript.setResultType(Long.class);

        this.removeByPrefixScript = new DefaultRedisScript<>();
        this.removeByPrefixScript.setScriptText(REMOVE_BY_PREFIX_LUA);
        this.removeByPrefixScript.setResultType(Long.class);
    }

    @Override
    public void addToInbox(Long userId, Long messageId, String briefContent, long timestamp) {
        String key = INBOX_KEY_PREFIX + userId;
        String member = messageId + ":" + briefContent;
        long ttlSeconds = (long) schatProperties.getRedis().getInbox().getExpireDays() * 86400;

        try {
            stringRedisTemplate.execute(
                    addToInboxScript,
                    Collections.singletonList(key),
                    String.valueOf(timestamp),
                    member,
                    String.valueOf(ttlSeconds)
            );
            log.debug("Inbox 写入成功: userId={}, messageId={}", userId, messageId);
        } catch (Exception e) {
            log.error("Inbox 写入失败: userId={}, messageId={}", userId, messageId, e);
            throw e;
        }
    }

    @Override
    public void removeFromInbox(Long userId, Long messageId) {
        String key = INBOX_KEY_PREFIX + userId;
        String memberPrefix = messageId.toString();

        try {
            Long removed = stringRedisTemplate.execute(
                    removeByPrefixScript,
                    Collections.singletonList(key),
                    memberPrefix
            );
            if (removed != null && removed > 0) {
                log.debug("Inbox 移除成功: userId={}, messageId={}", userId, messageId);
            } else {
                log.debug("Inbox 中未找到消息: userId={}, messageId={}", userId, messageId);
            }
        } catch (Exception e) {
            log.error("Inbox 移除失败: userId={}, messageId={}", userId, messageId, e);
            throw e;
        }
    }

    @Override
    public List<InboxMessage> getInbox(Long userId) {
        String key = INBOX_KEY_PREFIX + userId;

        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);

            if (tuples == null || tuples.isEmpty()) {
                return Collections.emptyList();
            }

            List<InboxMessage> result = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String member = tuple.getValue();
                Double score = tuple.getScore();
                if (member == null || score == null) {
                    continue;
                }

                InboxMessage msg = parseMember(member, score.longValue());
                if (msg != null) {
                    result.add(msg);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Inbox 查询失败: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * 解析 ZSet member 为 InboxMessage。
     * Member 格式: {messageId}:{briefContent}
     * 以第一个 ':' 分割，前半部分为 messageId，后半部分为 briefContent。
     */
    private InboxMessage parseMember(String member, long timestamp) {
        int colonIndex = member.indexOf(':');
        if (colonIndex <= 0 || colonIndex >= member.length() - 1) {
            log.warn("Inbox member 格式异常，跳过: {}", member);
            return null;
        }

        try {
            InboxMessage msg = new InboxMessage();
            msg.setMessageId(Long.parseLong(member.substring(0, colonIndex)));
            msg.setBriefContent(member.substring(colonIndex + 1));
            msg.setTimestamp(timestamp);
            return msg;
        } catch (NumberFormatException e) {
            log.warn("Inbox member messageId 解析失败: {}", member, e);
            return null;
        }
    }
}
