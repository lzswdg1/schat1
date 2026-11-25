package com.zw.sserver.mq.impl;

import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.InboxMessage;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisInbox 属性测试
 * 使用 jqwik 框架，每个属性测试至少运行 100 次迭代
 */
class RedisInboxPropertyTest {

    // ========== Generators ==========

    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    @Provide
    Arbitrary<Long> messageIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    @Provide
    Arbitrary<String> briefContents() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
    }

    @Provide
    Arbitrary<Long> timestamps() {
        return Arbitraries.longs().between(1_000_000_000_000L, 2_000_000_000_000L);
    }

    @Provide
    Arbitrary<InboxMessage> inboxMessages() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, Long.MAX_VALUE),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                Arbitraries.longs().between(1_000_000_000_000L, 2_000_000_000_000L)
        ).as((msgId, brief, ts) -> {
            InboxMessage msg = new InboxMessage();
            msg.setMessageId(msgId);
            msg.setBriefContent(brief);
            msg.setTimestamp(ts);
            return msg;
        });
    }

    /**
     * Generate a list of InboxMessages with unique messageIds.
     */
    @Provide
    Arbitrary<List<InboxMessage>> uniqueInboxMessageLists() {
        return inboxMessages().list().ofMinSize(0).ofMaxSize(20)
                .map(list -> {
                    // Deduplicate by messageId, keeping first occurrence
                    Map<Long, InboxMessage> seen = new LinkedHashMap<>();
                    for (InboxMessage msg : list) {
                        seen.putIfAbsent(msg.getMessageId(), msg);
                    }
                    return new ArrayList<>(seen.values());
                });
    }

    // ========== Helper ==========

    @SuppressWarnings("unchecked")
    private record InboxFixture(
            RedisInboxServiceImpl inboxService,
            StringRedisTemplate stringRedisTemplate,
            ZSetOperations<String, String> zSetOps,
            SchatProperties schatProperties
    ) {}

    @SuppressWarnings("unchecked")
    private InboxFixture createFixture() {
        return createFixture(7);
    }

    @SuppressWarnings("unchecked")
    private InboxFixture createFixture(int expireDays) {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        SchatProperties props = new SchatProperties();
        props.getInstance().setId("test-node:8082");
        props.getRedis().getInbox().setExpireDays(expireDays);

        // Allow Lua script execution
        lenient().when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any()
        )).thenReturn(1L);

        RedisInboxServiceImpl inboxService = new RedisInboxServiceImpl(stringRedisTemplate, props);
        return new InboxFixture(inboxService, stringRedisTemplate, zSetOps, props);
    }

    // ========== Property Tests ==========

    // Feature: redis-message-routing, Property 14: Inbox 与 MySQL 合并去重
    // **Validates: Requirements 5.7**
    @Property(tries = 100)
    void mergeDeduplicatesCorrectly(
            @ForAll("uniqueInboxMessageLists") List<InboxMessage> inboxSet,
            @ForAll("uniqueInboxMessageLists") List<InboxMessage> mysqlSet) {

        // The merge logic: I ∪ M deduplicated by messageId, sorted by messageId ascending.
        // This is a pure logic test for the merge algorithm that will be used in ChatEndpoint.

        // Act: merge using the same algorithm that ChatEndpoint will use
        List<InboxMessage> merged = mergeAndDeduplicate(inboxSet, mysqlSet);

        // Collect all unique messageIds from both sets
        Set<Long> allUniqueIds = new HashSet<>();
        for (InboxMessage msg : inboxSet) {
            allUniqueIds.add(msg.getMessageId());
        }
        for (InboxMessage msg : mysqlSet) {
            allUniqueIds.add(msg.getMessageId());
        }

        // Property 1: merged result size equals the number of unique messageIds
        assertEquals(allUniqueIds.size(), merged.size(),
                "Merged result size should equal the number of unique messageIds from both sets");

        // Property 2: all messageIds in merged result are unique
        Set<Long> mergedIds = merged.stream()
                .map(InboxMessage::getMessageId)
                .collect(Collectors.toSet());
        assertEquals(merged.size(), mergedIds.size(),
                "Merged result should have no duplicate messageIds");

        // Property 3: merged result contains exactly the union of messageIds
        assertEquals(allUniqueIds, mergedIds,
                "Merged result should contain exactly the union of messageIds from both sets");

        // Property 4: merged result is sorted by messageId ascending
        for (int i = 1; i < merged.size(); i++) {
            assertTrue(merged.get(i).getMessageId() > merged.get(i - 1).getMessageId(),
                    "Merged result should be sorted by messageId ascending");
        }
    }

    // Feature: redis-message-routing, Property 15: Inbox 生命周期一致性
    // **Validates: Requirements 5.6, 5.8**
    @Property(tries = 100)
    void inboxLifecycleConsistency(
            @ForAll("userIds") Long userId,
            @ForAll("messageIds") Long messageId,
            @ForAll("briefContents") String briefContent,
            @ForAll("timestamps") long timestamp) {

        InboxFixture fixture = createFixture();
        String expectedKey = "schat:inbox:" + userId;
        String expectedMember = messageId + ":" + briefContent;

        // Act 1: addToInbox should call the Lua script
        fixture.inboxService.addToInbox(userId, messageId, briefContent, timestamp);

        // Assert 1: Lua script was executed with correct arguments
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);

        verify(fixture.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keysCaptor.capture(),
                argsCaptor.capture(),  // ARGV[1] = timestamp
                argsCaptor.capture(),  // ARGV[2] = member
                argsCaptor.capture()   // ARGV[3] = TTL
        );

        List<String> capturedKeys = keysCaptor.getValue();
        assertEquals(1, capturedKeys.size());
        assertEquals(expectedKey, capturedKeys.get(0),
                "Lua script should be called with the correct inbox key");

        // Act 2: removeFromInbox should call Lua script (ZSCAN + ZREM)
        // The implementation now uses a Lua script instead of direct ZSet ops
        // Reset invocation count to cleanly verify the remove call
        clearInvocations(fixture.stringRedisTemplate);

        fixture.inboxService.removeFromInbox(userId, messageId);

        // Assert 2: Lua script was called for removal with key and messageId prefix
        ArgumentCaptor<List<String>> removeKeysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> removeArgsCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                removeKeysCaptor.capture(),
                removeArgsCaptor.capture()
        );
        assertEquals(expectedKey, removeKeysCaptor.getValue().get(0),
                "Remove Lua script should target the correct inbox key");
        assertEquals(messageId.toString(), removeArgsCaptor.getValue(),
                "Remove Lua script should receive messageId as prefix");
    }

    // Feature: redis-message-routing, Property 16: Inbox 过期时间为 7 天
    // **Validates: Requirements 5.9**
    @Property(tries = 100)
    void inboxTtlIs7Days(
            @ForAll("userIds") Long userId,
            @ForAll("messageIds") Long messageId,
            @ForAll("briefContents") String briefContent,
            @ForAll("timestamps") long timestamp) {

        InboxFixture fixture = createFixture();
        long expectedTtlSeconds = 7L * 86400; // 604800 seconds

        // Act: addToInbox
        fixture.inboxService.addToInbox(userId, messageId, briefContent, timestamp);

        // Assert: Lua script was called with TTL = 604800
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                anyList(),
                argsCaptor.capture(),  // ARGV[1] = timestamp
                argsCaptor.capture(),  // ARGV[2] = member
                argsCaptor.capture()   // ARGV[3] = TTL
        );

        // The third vararg is the TTL
        List<String> allArgs = argsCaptor.getAllValues();
        String ttlArg = allArgs.get(2); // Third argument is TTL
        assertEquals(String.valueOf(expectedTtlSeconds), ttlArg,
                "Lua script should be called with TTL = 604800 (7 days in seconds)");
    }

    // Feature: redis-message-routing, Property 22: Inbox TTL 仅首次设置（补丁 3）
    // **Validates: Requirements 5.9, 补丁 3**
    @Property(tries = 100)
    void inboxTtlSetOnlyOnCreation(
            @ForAll("userIds") Long userId,
            @ForAll("messageIds") Long messageId1,
            @ForAll("messageIds") Long messageId2,
            @ForAll("briefContents") String briefContent,
            @ForAll("timestamps") long timestamp) {

        // This test verifies the Lua script logic: EXISTS check before ZADD,
        // EXPIRE only when existed==0 (key didn't exist before).

        InboxFixture fixture = createFixture();

        // Verify the Lua script text contains the correct logic
        // The script should: 1) check EXISTS, 2) ZADD, 3) conditionally EXPIRE only if key was new
        String luaScript = getLuaScriptText(fixture.inboxService);

        // Assert: Script contains EXISTS check
        assertTrue(luaScript.contains("EXISTS"),
                "Lua script must contain EXISTS check to detect if key already exists");

        // Assert: Script contains conditional EXPIRE (only when existed == 0)
        assertTrue(luaScript.contains("existed == 0") || luaScript.contains("existed==0"),
                "Lua script must conditionally EXPIRE only when key didn't exist (existed == 0)");

        // Assert: Script contains ZADD
        assertTrue(luaScript.contains("ZADD"),
                "Lua script must contain ZADD to add the message");

        // Assert: Script contains EXPIRE
        assertTrue(luaScript.contains("EXPIRE"),
                "Lua script must contain EXPIRE to set TTL");

        // Assert: EXISTS is called BEFORE ZADD (order matters for correctness)
        int existsIndex = luaScript.indexOf("EXISTS");
        int zaddIndex = luaScript.indexOf("ZADD");
        int expireIndex = luaScript.indexOf("EXPIRE");
        assertTrue(existsIndex < zaddIndex,
                "EXISTS must be called before ZADD to correctly detect key creation");
        assertTrue(zaddIndex < expireIndex,
                "ZADD must be called before conditional EXPIRE");

        // Functional verification: call addToInbox twice and verify the script is called each time
        // with the same TTL value (the script handles the conditional logic internally)
        fixture.inboxService.addToInbox(userId, messageId1, briefContent, timestamp);
        fixture.inboxService.addToInbox(userId, messageId2, briefContent, timestamp + 1000);

        // Verify the Lua script was called exactly twice
        verify(fixture.stringRedisTemplate, times(2)).execute(
                any(DefaultRedisScript.class), anyList(), any(), any(), any());

        // Both calls should pass the same TTL value (604800)
        // The Lua script internally decides whether to actually set EXPIRE
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.stringRedisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture()
        );

        List<String> allArgs = argsCaptor.getAllValues();
        // Each call has 3 args, so 6 total. TTL is at index 2 and 5.
        String ttl1 = allArgs.get(2);
        String ttl2 = allArgs.get(5);
        assertEquals(ttl1, ttl2,
                "Both addToInbox calls should pass the same TTL to the Lua script");
        assertEquals("604800", ttl1,
                "TTL should be 604800 seconds (7 days)");
    }

    // ========== Helper Methods ==========

    /**
     * Merge and deduplicate Inbox messages and MySQL messages by messageId,
     * sorted by messageId ascending. This is the algorithm ChatEndpoint will use.
     * <p>
     * Inbox messages take precedence over MySQL messages for the same messageId.
     */
    static List<InboxMessage> mergeAndDeduplicate(List<InboxMessage> inbox, List<InboxMessage> mysql) {
        Map<Long, InboxMessage> merged = new TreeMap<>(); // TreeMap for natural ordering by messageId

        // Add MySQL messages first
        for (InboxMessage msg : mysql) {
            merged.put(msg.getMessageId(), msg);
        }

        // Add Inbox messages (overwrite MySQL if same messageId)
        for (InboxMessage msg : inbox) {
            merged.put(msg.getMessageId(), msg);
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * Extract the Lua script text from RedisInboxServiceImpl via reflection.
     */
    private String getLuaScriptText(RedisInboxServiceImpl inboxService) {
        try {
            var field = RedisInboxServiceImpl.class.getDeclaredField("ADD_TO_INBOX_LUA");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ADD_TO_INBOX_LUA field", e);
        }
    }
}
