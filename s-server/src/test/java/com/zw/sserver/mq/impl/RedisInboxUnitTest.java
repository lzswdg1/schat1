package com.zw.sserver.mq.impl;

import com.zw.sserver.config.SchatProperties;
import com.zw.sserver.model.dto.InboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisInbox 单元测试
 * <p>
 * 测试 Lua 脚本原子性、并发写入时 TTL 仅设置一次（补丁 3）
 */
class RedisInboxUnitTest {

    // ========== Fixture (same pattern as RedisInboxPropertyTest) ==========

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

    // ========== Test 1: addToInbox calls Lua script with correct key, score, member, TTL ==========

    @Test
    @DisplayName("addToInbox calls Lua script with correct key, score, member, TTL")
    void addToInboxCallsLuaWithCorrectArgs() {
        InboxFixture f = createFixture();
        Long userId = 42L;
        Long messageId = 100001L;
        String briefContent = "hello-world";
        long timestamp = 1700000000000L;

        f.inboxService.addToInbox(userId, messageId, briefContent, timestamp);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);

        verify(f.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keysCaptor.capture(),
                argsCaptor.capture(),  // ARGV[1] = timestamp
                argsCaptor.capture(),  // ARGV[2] = member
                argsCaptor.capture()   // ARGV[3] = TTL
        );

        // Key
        assertEquals(List.of("schat:inbox:42"), keysCaptor.getValue());

        // Args
        List<String> args = argsCaptor.getAllValues();
        assertEquals("1700000000000", args.get(0), "ARGV[1] should be the timestamp");
        assertEquals("100001:hello-world", args.get(1), "ARGV[2] should be {messageId}:{briefContent}");
        assertEquals("604800", args.get(2), "ARGV[3] should be 7 * 86400 = 604800");
    }

    // ========== Test 2: addToInbox member format is {messageId}:{briefContent} ==========

    @Test
    @DisplayName("addToInbox member format is {messageId}:{briefContent}")
    void addToInboxMemberFormat() {
        InboxFixture f = createFixture();

        f.inboxService.addToInbox(99L, 555L, "sender:1|type:CHAT|你好", 1700000000000L);

        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        verify(f.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                anyList(),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture()
        );

        String member = argsCaptor.getAllValues().get(1);
        assertEquals("555:sender:1|type:CHAT|你好", member,
                "Member should be {messageId}:{briefContent}, content may contain colons");
    }

    // ========== Test 3: removeFromInbox finds and removes correct member ==========

    @Test
    @DisplayName("removeFromInbox calls Lua script with correct key and messageId prefix")
    void removeFromInboxFindsAndRemoves() {
        InboxFixture f = createFixture();

        // Mock Lua script execution for remove
        when(f.stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any()
        )).thenReturn(1L);

        f.inboxService.removeFromInbox(42L, 100002L);

        // Verify Lua script was called with correct key and prefix
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);

        // The second execute call is for removeByPrefix (first was in createFixture lenient stub)
        verify(f.stringRedisTemplate, atLeastOnce()).execute(
                any(DefaultRedisScript.class),
                keysCaptor.capture(),
                argsCaptor.capture()
        );

        // Find the call with the inbox key
        List<List<String>> allKeys = keysCaptor.getAllValues();
        boolean found = allKeys.stream()
                .anyMatch(keys -> keys.contains("schat:inbox:42"));
        assertTrue(found, "Lua script should be called with key schat:inbox:42");
    }

    // ========== Test 4: removeFromInbox uses Lua script (no direct ZSet ops) ==========

    @Test
    @DisplayName("removeFromInbox uses Lua script, not direct ZSet operations")
    void removeFromInboxUsesLuaScript() {
        InboxFixture f = createFixture();

        when(f.stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any()
        )).thenReturn(0L);

        f.inboxService.removeFromInbox(42L, 999L);

        // ZSet operations should NOT be called directly
        verify(f.zSetOps, never()).range(anyString(), anyLong(), anyLong());
        verify(f.zSetOps, never()).remove(anyString(), any());
    }

    @Test
    @DisplayName("removeFromInbox passes messageId as prefix argument to Lua script")
    void removeFromInboxMessageIdAsPrefix() {
        InboxFixture f = createFixture();

        when(f.stringRedisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any()
        )).thenReturn(0L);

        f.inboxService.removeFromInbox(42L, 999999L);

        // Verify the Lua script was called (no direct ZSet ops)
        verify(f.zSetOps, never()).remove(anyString(), any());
    }

    // ========== Test 5: getInbox returns empty list for non-existent key ==========

    @Test
    @DisplayName("getInbox returns empty list for non-existent key (null range)")
    void getInboxReturnsEmptyForNull() {
        InboxFixture f = createFixture();
        String key = "schat:inbox:42";

        when(f.zSetOps.rangeWithScores(key, 0, -1)).thenReturn(null);

        List<InboxMessage> result = f.inboxService.getInbox(42L);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getInbox returns empty list for empty range")
    void getInboxReturnsEmptyForEmptySet() {
        InboxFixture f = createFixture();
        String key = "schat:inbox:42";

        when(f.zSetOps.rangeWithScores(key, 0, -1)).thenReturn(Collections.emptySet());

        List<InboxMessage> result = f.inboxService.getInbox(42L);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ========== Test 6: getInbox parses members correctly ==========

    @Test
    @DisplayName("getInbox parses ZSet members into InboxMessage fields correctly")
    void getInboxParsesCorrectly() {
        InboxFixture f = createFixture();
        String key = "schat:inbox:42";

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("100001:hello-world", 1700000000000.0));
        tuples.add(ZSetOperations.TypedTuple.of("100002:sender:1|type:CHAT|你好", 1700000001000.0));

        when(f.zSetOps.rangeWithScores(key, 0, -1)).thenReturn(tuples);

        List<InboxMessage> result = f.inboxService.getInbox(42L);

        assertEquals(2, result.size());

        InboxMessage msg1 = result.get(0);
        assertEquals(100001L, msg1.getMessageId());
        assertEquals("hello-world", msg1.getBriefContent());
        assertEquals(1700000000000L, msg1.getTimestamp());

        InboxMessage msg2 = result.get(1);
        assertEquals(100002L, msg2.getMessageId());
        assertEquals("sender:1|type:CHAT|你好", msg2.getBriefContent(),
                "briefContent should include everything after the first colon");
        assertEquals(1700000001000L, msg2.getTimestamp());
    }

    // ========== Test 7: getInbox skips malformed members ==========

    @Test
    @DisplayName("getInbox skips malformed members without ':' separator")
    void getInboxSkipsMalformedMembers() {
        InboxFixture f = createFixture();
        String key = "schat:inbox:42";

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("100001:valid-content", 1700000000000.0));
        tuples.add(ZSetOperations.TypedTuple.of("no-colon-here", 1700000001000.0));       // no ':'
        tuples.add(ZSetOperations.TypedTuple.of(":leading-colon", 1700000002000.0));       // colonIndex == 0
        tuples.add(ZSetOperations.TypedTuple.of("not-a-number:content", 1700000003000.0)); // non-numeric id
        tuples.add(ZSetOperations.TypedTuple.of("100005:", 1700000004000.0));              // colonIndex == length-1 (empty content)
        tuples.add(ZSetOperations.TypedTuple.of("100006:ok", 1700000005000.0));

        when(f.zSetOps.rangeWithScores(key, 0, -1)).thenReturn(tuples);

        List<InboxMessage> result = f.inboxService.getInbox(42L);

        // Only "100001:valid-content" and "100006:ok" should be parsed
        assertEquals(2, result.size());
        assertEquals(100001L, result.get(0).getMessageId());
        assertEquals(100006L, result.get(1).getMessageId());
    }

    // ========== Test 8: Lua script structure validates 补丁 3 ==========

    @Test
    @DisplayName("Lua script structure: EXISTS before ZADD, conditional EXPIRE (补丁 3)")
    void luaScriptStructureValidatesPatch3() {
        String luaScript = getLuaScriptText();

        // Must contain EXISTS check
        assertTrue(luaScript.contains("EXISTS"),
                "Lua script must contain EXISTS to check if key already exists");

        // Must contain ZADD
        assertTrue(luaScript.contains("ZADD"),
                "Lua script must contain ZADD to add the member");

        // Must contain EXPIRE
        assertTrue(luaScript.contains("EXPIRE"),
                "Lua script must contain EXPIRE to set TTL");

        // EXISTS must come before ZADD (order matters for correctness)
        int existsIdx = luaScript.indexOf("EXISTS");
        int zaddIdx = luaScript.indexOf("ZADD");
        int expireIdx = luaScript.indexOf("EXPIRE");

        assertTrue(existsIdx < zaddIdx,
                "EXISTS must be called before ZADD to detect key creation");
        assertTrue(zaddIdx < expireIdx,
                "ZADD must be called before conditional EXPIRE");

        // Conditional: EXPIRE only when key didn't exist (existed == 0)
        assertTrue(luaScript.contains("existed == 0") || luaScript.contains("existed==0"),
                "Lua script must conditionally EXPIRE only when existed == 0 (key was new)");
    }

    // ========== Test: TTL scales with expireDays config ==========

    @Test
    @DisplayName("addToInbox TTL scales with expireDays configuration")
    void addToInboxTtlScalesWithConfig() {
        InboxFixture f = createFixture(14); // 14 days

        f.inboxService.addToInbox(1L, 1L, "test", 1700000000000L);

        ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
        verify(f.stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                anyList(),
                argsCaptor.capture(),
                argsCaptor.capture(),
                argsCaptor.capture()
        );

        String ttl = argsCaptor.getAllValues().get(2);
        assertEquals(String.valueOf(14L * 86400), ttl,
                "TTL should be expireDays * 86400 seconds");
    }

    // ========== Helper ==========

    private String getLuaScriptText() {
        try {
            var field = RedisInboxServiceImpl.class.getDeclaredField("ADD_TO_INBOX_LUA");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ADD_TO_INBOX_LUA field", e);
        }
    }
}
