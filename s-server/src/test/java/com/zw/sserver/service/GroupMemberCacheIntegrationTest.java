package com.zw.sserver.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zw.sserver.mapper.GroupMemberMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SCHAT_TEST_REDIS_PORT", matches = ".+")
class GroupMemberCacheIntegrationTest {

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static GroupMemberMapper members;
    private static LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private TransactionTemplate transactions;
    private GroupMemberCacheInvalidation invalidation;
    private GroupMemberCacheService cache;
    private ExecutorService workers;

    @BeforeAll
    static void connect() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("SCHAT_TEST_MYSQL_URL"), "root", "schat-test-only");
        try (var connection = source.getConnection()) {
            assertEquals("schat_test", connection.getCatalog());
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration/server").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(GroupMemberMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        members = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject())).getMapper(GroupMemberMapper.class);
        connections = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("SCHAT_TEST_REDIS_PORT")));
        connections.afterPropertiesSet();
        connections.start();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tb_group_cache_invalidation");
        jdbc.update("DELETE FROM tb_group_member");
        jdbc.update("DELETE FROM tb_group_info");
        jdbc.update("INSERT INTO tb_group_info (group_id, group_name, owner_id, member_count) VALUES (10, 'cache', 1, 1)");
        addMember(1);
        redis = new StringRedisTemplate(connections);
        redis.delete(List.of(GroupMemberCacheInvalidation.membersKey(10), GroupMemberCacheInvalidation.revisionKey(10)));
        transactions = new TransactionTemplate(manager);
        invalidation = new GroupMemberCacheInvalidation(jdbc, redis, manager);
        cache = new GroupMemberCacheService(redis, members, invalidation);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Test
    void invalidationRunsAfterCommitAndRollbackDoesNotInvalidateOrPublishUncommittedMembers() {
        assertEquals(Set.of(1L), cache.getMemberIds(10L));
        transactions.executeWithoutResult(status -> {
            addMember(2);
            cache.evict(10L);
            assertEquals(Set.of("1"), redis.opsForSet().members(GroupMemberCacheInvalidation.membersKey(10)));
            assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_group_cache_invalidation", Integer.class));
        assertEquals(Set.of(1L), cache.getMemberIds(10L));
        transactions.executeWithoutResult(status -> {
            addMember(2);
            cache.evict(10L);
            assertEquals(Set.of("1"), redis.opsForSet().members(GroupMemberCacheInvalidation.membersKey(10)));
        });
        assertFalse(Boolean.TRUE.equals(redis.hasKey(GroupMemberCacheInvalidation.membersKey(10))));
        assertEquals("1", redis.opsForValue().get(GroupMemberCacheInvalidation.revisionKey(10)));
        assertEquals(0L, publishedRevision());
        invalidation.retryPending();
        assertEquals(1L, publishedRevision());
        assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
    }

    @Test
    void delayedCacheMissCannotRefillTheOldSnapshotAfterInvalidation() throws Exception {
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        GroupMemberMapper controlled = mock(GroupMemberMapper.class);
        when(controlled.selectMemberIds(10L)).thenAnswer(invocation -> {
            List<Long> snapshot = members.selectMemberIds(10L);
            loaded.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return snapshot;
        });
        var delayedCache = new GroupMemberCacheService(redis, controlled, invalidation);
        Future<Set<Long>> old = workers.submit(() -> delayedCache.getMemberIds(10L));
        assertTrue(loaded.await(5, TimeUnit.SECONDS));
        try {
            transactions.executeWithoutResult(status -> {
                addMember(2);
                cache.evict(10L);
            });
        } finally {
            release.countDown();
        }
        assertEquals(Set.of(1L), old.get(5, TimeUnit.SECONDS));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(GroupMemberCacheInvalidation.membersKey(10))));
        assertEquals(Set.of(1L, 2L), delayedCache.getMemberIds(10L));
        assertEquals(Set.of(1L, 2L), delayedCache.getMemberIds(10L));
        verify(controlled, times(2)).selectMemberIds(10L);
    }

    @Test
    void anOlderDatabaseTransactionCannotPublishItsRepeatableReadSnapshot() throws Exception {
        transactions.executeWithoutResult(status -> {
            assertEquals(List.of(1L), members.selectMemberIds(10L));
            try {
                workers.submit(() -> transactions.executeWithoutResult(change -> {
                    addMember(2);
                    cache.evict(10L);
                })).get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            assertEquals(Set.of(1L), cache.getMemberIds(10L));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(GroupMemberCacheInvalidation.membersKey(10))));
        });
        assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureLeavesDurableWorkAndCachedMembershipDoesNotAuthorizeRemovedUsers() {
        assertEquals(Set.of(1L), cache.getMemberIds(10L));
        var unavailable = spy(redis);
        doThrow(new IllegalStateException("simulated Redis outage")).when(unavailable)
                .execute(any(RedisScript.class), anyList(), eq("1"));
        var pending = new GroupMemberCacheInvalidation(jdbc, unavailable, manager);
        transactions.executeWithoutResult(status -> {
            addMember(2);
            jdbc.update("DELETE FROM tb_group_member WHERE group_id = 10 AND user_id = 1");
            pending.invalidate(10);
        });
        assertEquals(Set.of(1L), cache.getMemberIds(10L));
        assertFalse(cache.isMember(10L, 1L));
        assertTrue(cache.isMember(10L, 2L));
        assertEquals(0L, publishedRevision());
        pending.retryPending();
        assertEquals(1, jdbc.queryForObject("SELECT failures FROM tb_group_cache_invalidation", Integer.class));
        jdbc.update("UPDATE tb_group_cache_invalidation SET next_attempt_at = CURRENT_TIMESTAMP(3)");
        new GroupMemberCacheInvalidation(jdbc, redis, manager).retryPending();
        assertEquals(1L, publishedRevision());
        assertEquals(Set.of(2L), cache.getMemberIds(10L));
    }

    @Test
    void reconciliationRestoresGenerationAfterRedisMetadataLoss() {
        transactions.executeWithoutResult(status -> {
            addMember(2);
            cache.evict(10L);
        });
        assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
        invalidation.retryPending();
        assertEquals(1L, publishedRevision());
        redis.delete(List.of(GroupMemberCacheInvalidation.membersKey(10), GroupMemberCacheInvalidation.revisionKey(10)));
        redis.opsForSet().add(GroupMemberCacheInvalidation.membersKey(10), "1");
        jdbc.update("UPDATE tb_group_cache_invalidation SET published_at = TIMESTAMPADD(SECOND, -61, CURRENT_TIMESTAMP(3))");
        invalidation.retryPending();
        assertEquals("1", redis.opsForValue().get(GroupMemberCacheInvalidation.revisionKey(10)));
        assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void delayedInvalidationCannotDeleteANewerCacheOrDowngradeItsGeneration() throws Exception {
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delayed = spy(redis);
        doAnswer(invocation -> {
            captured.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return redis.execute(invocation.getArgument(0, RedisScript.class), invocation.getArgument(1, List.class), "1");
        }).when(delayed).execute(any(RedisScript.class), anyList(), eq("1"));
        var first = new GroupMemberCacheInvalidation(jdbc, delayed, manager);
        Future<?> old = workers.submit(() -> transactions.executeWithoutResult(status -> {
            addMember(2);
            first.invalidate(10);
        }));
        assertTrue(captured.await(5, TimeUnit.SECONDS));
        try {
            transactions.executeWithoutResult(status -> {
                addMember(3);
                cache.evict(10L);
            });
            assertEquals(Set.of(1L, 2L, 3L), cache.getMemberIds(10L));
        } finally {
            release.countDown();
        }
        old.get(5, TimeUnit.SECONDS);
        assertEquals("2", redis.opsForValue().get(GroupMemberCacheInvalidation.revisionKey(10)));
        assertEquals(Set.of("1", "2", "3"), redis.opsForSet().members(GroupMemberCacheInvalidation.membersKey(10)));
        invalidation.retryPending();
        assertEquals(2L, publishedRevision());
    }

    @Test
    void emptyMembershipIsCachedWithoutTurningTheSentinelIntoAUser() {
        GroupMemberMapper empty = mock(GroupMemberMapper.class);
        when(empty.selectMemberIds(10L)).thenReturn(List.of());
        var emptyCache = new GroupMemberCacheService(redis, empty, invalidation);
        assertTrue(emptyCache.getMemberIds(10L).isEmpty());
        assertTrue(emptyCache.getMemberIds(10L).isEmpty());
        verify(empty).selectMemberIds(10L);
    }

    @Test
    void afterCommitInvalidationWorksWithAOneConnectionPool() {
        cache.getMemberIds(10L);
        try (var source = new com.zaxxer.hikari.HikariDataSource()) {
            source.setJdbcUrl(System.getenv("SCHAT_TEST_MYSQL_URL"));
            source.setUsername("root");
            source.setPassword("schat-test-only");
            source.setMinimumIdle(1);
            source.setMaximumPoolSize(1);
            source.setConnectionTimeout(500);
            var singleJdbc = new JdbcTemplate(source);
            var singleManager = new DataSourceTransactionManager(source);
            var singleInvalidation = new GroupMemberCacheInvalidation(singleJdbc, redis, singleManager);
            new TransactionTemplate(singleManager).executeWithoutResult(status -> {
                singleJdbc.update("INSERT INTO tb_group_member (group_id, user_id, role) VALUES (10, 2, 'MEMBER')");
                singleInvalidation.invalidate(10);
            });
            assertEquals("1", redis.opsForValue().get(GroupMemberCacheInvalidation.revisionKey(10)));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(GroupMemberCacheInvalidation.membersKey(10))));
            singleInvalidation.retryPending();
            assertEquals(1L, publishedRevision());
            assertEquals(Set.of(1L, 2L), cache.getMemberIds(10L));
        }
    }

    private void addMember(long userId) {
        jdbc.update("INSERT INTO tb_group_member (group_id, user_id, role) VALUES (10, ?, 'MEMBER')", userId);
    }

    private long publishedRevision() {
        return jdbc.queryForObject("SELECT published_revision FROM tb_group_cache_invalidation WHERE group_id = 10", Long.class);
    }
}
