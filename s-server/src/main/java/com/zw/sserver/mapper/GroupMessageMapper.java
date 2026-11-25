package com.zw.sserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zw.sserver.model.entity.GroupMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupMessageMapper extends BaseMapper<GroupMessage> {
    @Select("SELECT * FROM tb_group_message WHERE message_id = #{messageId} FOR UPDATE")
    GroupMessage selectForUpdate(@Param("messageId") Long messageId);

    @Select("SELECT COUNT(*) FROM tb_group_message gm " +
            "JOIN tb_group_member mem ON mem.group_id = gm.group_id " +
            "WHERE gm.group_id = #{groupId} AND mem.user_id = #{userId} " +
            "AND gm.sender_id <> #{userId} " +
            "AND gm.seq > #{lastReadSeq} AND gm.send_time >= mem.join_time")
    long countAfterSeq(@Param("groupId") Long groupId,
                       @Param("userId") Long userId,
                       @Param("lastReadSeq") Long lastReadSeq);
}
