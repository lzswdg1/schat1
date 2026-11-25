package com.zw.sserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zw.sserver.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
* @description 针对表【tb_chat_message(离线加密消息表)】的数据库操作Mapper
* @createDate 2026-02-26 20:36:31
* @Entity com.zw.sserver.model.entity.ChatMessage
*/
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM tb_chat_message WHERE message_id = #{messageId} FOR UPDATE")
    ChatMessage selectForUpdate(@org.apache.ibatis.annotations.Param("messageId") Long messageId);

    /**
     * 删除两个用户之间的所有聊天记录（双向）
     */
    @Delete("DELETE FROM tb_chat_message WHERE (sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1})")
    int deleteMessages(@Param("userId1") Long userId1, @Param("userId2") Long userId2);
}




