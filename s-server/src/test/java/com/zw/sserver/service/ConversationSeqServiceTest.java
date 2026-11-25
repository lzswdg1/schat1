package com.zw.sserver.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationSeqServiceTest {

    @Test
    void privatePeersUseTheSameLockedCounter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("private:2:9"))).thenReturn(41L);
        ConversationSeqService service = new ConversationSeqService(jdbc);
        assertEquals(42L, service.nextSeq(9L, 2L));
        var order = inOrder(jdbc);
        order.verify(jdbc).update(contains("ON DUPLICATE KEY"), eq("private:2:9"));
        order.verify(jdbc).queryForObject(contains("FOR UPDATE"), eq(Long.class), eq("private:2:9"));
        order.verify(jdbc).update(contains("SET last_seq"), eq(42L), eq("private:2:9"));
    }

    @Test
    void groupCounterHasItsOwnNamespace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("group:10"))).thenReturn(8L);
        assertEquals(9L, new ConversationSeqService(jdbc).nextGroupSeq(10L));
    }
}
