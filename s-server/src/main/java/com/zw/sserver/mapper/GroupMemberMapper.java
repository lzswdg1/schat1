package com.zw.sserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zw.sserver.model.entity.GroupMember;
import com.zw.sserver.model.vo.GroupMemberVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    /**
     * 查询群的所有成员 userId
     */
    @Select("SELECT user_id FROM tb_group_member WHERE group_id = #{groupId}")
    List<Long> selectMemberIds(@Param("groupId") Long groupId);

    /**
     * 查询用户加入的所有群 ID
     */
    @Select("SELECT group_id FROM tb_group_member WHERE user_id = #{userId}")
    List<Long> selectGroupIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询群成员详情（关联 tb_user 获取昵称和头像，不返回 accountId）
     */
    @Select("SELECT m.user_id, COALESCE(m.nickname, u.nickname) AS nickname, u.avatar, m.role, m.muted " +
            "FROM tb_group_member m LEFT JOIN tb_user u ON m.user_id = u.id " +
            "WHERE m.group_id = #{groupId}")
    List<GroupMemberVO> selectMemberDetails(@Param("groupId") Long groupId);

    @Select("SELECT COALESCE(last_read_seq, 0) FROM tb_group_member " +
            "WHERE group_id = #{groupId} AND user_id = #{userId}")
    Long selectLastReadSeq(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Update("UPDATE tb_group_member SET last_read_seq = #{seq} " +
            "WHERE group_id = #{groupId} AND user_id = #{userId} AND last_read_seq < #{seq}")
    int advanceLastReadSeq(@Param("groupId") Long groupId,
                           @Param("userId") Long userId,
                           @Param("seq") Long seq);
}
