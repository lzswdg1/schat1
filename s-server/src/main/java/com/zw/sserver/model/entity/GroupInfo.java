package com.zw.sserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 群组信息表
 */
@TableName("tb_group_info")
@Data
public class GroupInfo implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long groupId;

    /** 群名称 */
    private String groupName;

    /** 群主 userId */
    private Long ownerId;

    /** 群头像 URL */
    private String avatar;

    /** 群公告 */
    private String notice;

    /** 群成员上限，默认 1000 */
    private Integer maxMembers;

    /** 当前成员数 */
    private Integer memberCount;

    private Date createTime;
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
