package com.zw.sserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zw.sserver.mapper.GroupMemberMapper;
import com.zw.sserver.model.entity.GroupMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class GroupMemberCacheService {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberCacheService.class);
    private static final String EMPTY = "_";
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            local result = {redis.call('GET', KEYS[1]) or '0', redis.call('EXISTS', KEYS[2])}
            for _, member in ipairs(redis.call('SMEMBERS', KEYS[2])) do
                table.insert(result, member)
            end
            return result
            """, List.class);
    private static final DefaultRedisScript<Long> FILL = new DefaultRedisScript<>("""
            if (redis.call('GET', KEYS[1]) or '0') ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[2])
            if #ARGV == 2 then
                redis.call('SADD', KEYS[2], '_')
            else
                for index = 3, #ARGV do redis.call('SADD', KEYS[2], ARGV[index]) end
            end
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final GroupMemberMapper members;
    private final GroupMemberCacheInvalidation invalidation;

    public GroupMemberCacheService(StringRedisTemplate redis, GroupMemberMapper members,
                                   GroupMemberCacheInvalidation invalidation) {
        this.redis = redis;
        this.members = members;
        this.invalidation = invalidation;
    }

    public Set<Long> getMemberIds(Long groupId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return load(groupId);
        List<?> snapshot = null;
        List<String> keys = List.of(GroupMemberCacheInvalidation.revisionKey(groupId),
                GroupMemberCacheInvalidation.membersKey(groupId));
        try {
            snapshot = redis.execute(READ, keys);
            if (snapshot != null && snapshot.size() >= 2 && "1".equals(String.valueOf(snapshot.get(1)))) {
                Set<Long> cached = new HashSet<>();
                for (int index = 2; index < snapshot.size(); index++) {
                    String member = String.valueOf(snapshot.get(index));
                    if (!EMPTY.equals(member)) cached.add(Long.parseLong(member));
                }
                return cached;
            }
        } catch (Exception exception) {
            log.debug("群成员缓存读取失败，回源数据库: groupId={}", groupId, exception);
        }

        Set<Long> loaded = load(groupId);
        if (snapshot != null && snapshot.size() >= 2) {
            List<String> arguments = new ArrayList<>();
            arguments.add(String.valueOf(snapshot.get(0)));
            arguments.add("3600");
            loaded.forEach(memberId -> arguments.add(String.valueOf(memberId)));
            try {
                redis.execute(FILL, keys, arguments.toArray());
            } catch (Exception exception) {
                log.debug("群成员缓存回填失败，继续使用数据库结果: groupId={}", groupId, exception);
            }
        }
        return loaded;
    }

    public boolean isMember(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> query = new LambdaQueryWrapper<>();
        query.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        return members.selectCount(query) > 0;
    }

    public void evict(Long groupId) {
        invalidation.invalidate(groupId);
    }

    public void addMember(Long groupId, Long userId) {
        evict(groupId);
    }

    public void removeMember(Long groupId, Long userId) {
        evict(groupId);
    }

    private Set<Long> load(Long groupId) {
        List<Long> loaded = members.selectMemberIds(groupId);
        return loaded == null ? Set.of() : new HashSet<>(loaded);
    }
}
