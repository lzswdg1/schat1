package com.zw.suser.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 好友关系表
 * @TableName tb_friend_relation
 */
@TableName(value ="tb_friend_relation")
@Data
public class FriendRelation implements Serializable {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户1 ID (约定 user_id_1 < user_id_2)
     */
    @TableField("user_id_1")
    private Long userId1;

    /**
     * 用户2 ID
     */
    @TableField("user_id_2")
    private Long userId2;

    /**
     * 成为好友的时间
     */
    private Date createTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

}