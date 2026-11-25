package com.zw.sserver.service.impl;

import com.zw.sserver.domain.session.service.SessionCachePort;
import com.zw.sserver.model.vo.SessionVO;
import com.zw.sserver.domain.session.model.ConversationSession;
import com.zw.sserver.service.GroupReadCursorService;
import com.zw.sserver.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class SessionServiceImpl implements SessionService {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private GroupReadCursorService groupReadCursorService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SessionCachePort sessionCachePort;

    @Override
    public List<SessionVO> getSessionList(Long userId) {
        List<SessionVO> cached = sessionCachePort.get(userId);
        if (cached != null && cached.stream().noneMatch(session -> session.getLastMessageId() == null
                && session.getLastTime() != null && session.getLastTime() > 0)) {
            return cached;
        }
        List<SessionVO> sessions = new ArrayList<>();
        sessions.addAll(getPrivateSessions(userId));
        sessions.addAll(getGroupSessions(userId));
        // 按最后消息时间倒序
        sessions.sort((a, b) -> Long.compare(b.getLastTime() != null ? b.getLastTime() : 0,
                                              a.getLastTime() != null ? a.getLastTime() : 0));
        sessionCachePort.put(userId, sessions);
        return sessions;
    }

    /**
     * 私聊会话：从 tb_chat_message 中找出当前用户参与的所有对话伙伴，
     * 取每个对话的最新一条消息。
     */
    private List<SessionVO> getPrivateSessions(Long userId) {
        // 子查询：找出每个对话伙伴的最新 message_id
        String sql = """
            SELECT m.message_id, m.sender_id, m.receiver_id, m.cipher_content, m.send_time, m.is_read,
                   u.id AS peer_id, u.nickname AS peer_name, u.avatar AS peer_avatar
            FROM tb_chat_message m
            JOIN (
                SELECT peer_id, MAX(message_id) AS max_msg_id
                FROM (
                    SELECT receiver_id AS peer_id, message_id FROM tb_chat_message WHERE sender_id = ?
                    UNION ALL
                    SELECT sender_id AS peer_id, message_id FROM tb_chat_message WHERE receiver_id = ?
                ) t
                GROUP BY peer_id
            ) latest ON m.message_id = latest.max_msg_id
            JOIN tb_user u ON u.id = latest.peer_id
            ORDER BY m.send_time DESC
            """;

        List<SessionVO> result = jdbcTemplate.query(sql, (rs, rowNum) -> {
            long peerId = rs.getLong("peer_id");
            ConversationSession session = ConversationSession.privateSession(
                    peerId,
                    rs.getString("peer_name"),
                    rs.getString("peer_avatar"),
                    extractPreview(rs.getString("cipher_content")),
                    rs.getTimestamp("send_time").getTime(),
                    0
            );
            SessionVO vo = toVO(session);
            vo.setLastMessageId(String.valueOf(rs.getLong("message_id")));
            return vo;
        }, userId, userId);

        // 补充私聊未读数：对方发给我的、is_read=0 的消息数
        for (SessionVO vo : result) {
            String unreadSql = "SELECT COUNT(*) FROM tb_chat_message WHERE sender_id = ? AND receiver_id = ? AND is_read < 2";
            Integer unread = jdbcTemplate.queryForObject(unreadSql, Integer.class, vo.getTargetId(), userId);
            vo.setUnreadCount(unread != null ? unread : 0);
        }
        return result;
    }

    /**
     * 群聊会话：从 tb_group_member 找出用户加入的群，
     * 再从 tb_group_message 取每个群的最新消息。
     */
    private List<SessionVO> getGroupSessions(Long userId) {
        String sql = """
            SELECT g.group_id, g.group_name, g.avatar AS group_avatar,
                   gm.message_id, gm.cipher_content, gm.send_time
            FROM tb_group_member mem
            JOIN tb_group_info g ON g.group_id = mem.group_id
            LEFT JOIN tb_group_message gm ON gm.message_id = (
                SELECT MAX(message_id) FROM tb_group_message WHERE group_id = mem.group_id
            )
            WHERE mem.user_id = ?
            ORDER BY COALESCE(gm.send_time, g.create_time) DESC
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long groupId = rs.getLong("group_id");
            java.sql.Timestamp sendTime = rs.getTimestamp("send_time");
            ConversationSession session = ConversationSession.groupSession(
                    groupId,
                    rs.getString("group_name"),
                    rs.getString("group_avatar"),
                    extractPreview(rs.getString("cipher_content")),
                    sendTime != null ? sendTime.getTime() : 0L,
                    groupReadCursorService.getUnreadCount(userId, groupId)
            );
            SessionVO vo = toVO(session);
            Object messageId = rs.getObject("message_id");
            vo.setLastMessageId(messageId == null ? null : messageId.toString());
            return vo;
        }, userId);
    }

    private SessionVO toVO(ConversationSession session) {
        SessionVO vo = new SessionVO();
        vo.setId(session.getId());
        vo.setType(session.getType().getCode());
        vo.setTargetId(session.getTargetId());
        vo.setTargetName(session.getTargetName());
        vo.setTargetAvatar(session.getTargetAvatar());
        vo.setLastMessage(session.getLastMessage());
        vo.setLastTime(session.getLastTime());
        vo.setUnreadCount(session.getUnreadCount());
        return vo;
    }

    /**
     * 从 cipher_content（JSON 格式的消息体）中提取内容摘要
     * 使用 ObjectMapper 安全解析，避免手动截取 JSON 字符串导致的转义问题
     */
    private String extractPreview(String cipherContent) {
        if (cipherContent == null || cipherContent.isEmpty()) return "";
        try {
            JsonNode node = objectMapper.readTree(cipherContent);
            if (node.path("recalled").asBoolean(false) || "RECALL".equalsIgnoreCase(node.path("msgType").asText())) {
                return "[消息已撤回]";
            }
            if (node.has("content")) {
                String content = node.get("content").asText("");
                return content.length() > 50 ? content.substring(0, 50) + "..." : content;
            }
            return "[消息]";
        } catch (Exception e) {
            // 非 JSON 格式，直接截取原文
            return cipherContent.length() > 50 ? cipherContent.substring(0, 50) + "..." : cipherContent;
        }
    }
}
