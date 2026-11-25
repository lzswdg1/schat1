package com.zw.sserver.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ConversationSeqService {

    private final JdbcTemplate jdbcTemplate;

    public ConversationSeqService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSeq(Long userA, Long userB) {
        return next("private:" + Math.min(userA, userB) + ":" + Math.max(userA, userB));
    }

    public long nextGroupSeq(Long groupId) {
        return next("group:" + groupId);
    }

    private long next(String conversationKey) {
        jdbcTemplate.update("""
                INSERT INTO tb_conversation_sequence (conversation_key, last_seq) VALUES (?, 0)
                ON DUPLICATE KEY UPDATE last_seq = last_seq
                """, conversationKey);
        Long current = jdbcTemplate.queryForObject("""
                SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key = ? FOR UPDATE
                """, Long.class, conversationKey);
        long next = Math.addExact(java.util.Objects.requireNonNull(current), 1L);
        jdbcTemplate.update("UPDATE tb_conversation_sequence SET last_seq = ? WHERE conversation_key = ?",
                next, conversationKey);
        return next;
    }
}
