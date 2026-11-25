package com.zw.sserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 群成员表
 */
@TableName("tb_group_member")
@Data
public class GroupMember implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;

    /**
     * 角色：OWNER(群主), ADMIN(管理员), MEMBER(普通成员)
     */
    private String role;

    /** 群内昵称（可选） */
    private String nickname;

    /** 是否免打扰：0-否，1-是 */
    private Integer muted;

    private Date joinTime;

    /** 已持久化的群消息读取位置（群会话 seq） */
    private Long lastReadSeq;

    private static final long serialVersionUID = 1L;
}
