package com.zw.suser.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 好友申请流转表
 * @TableName tb_friend_request
 */
@TableName(value ="tb_friend_request")
@Data
public class FriendRequest implements Serializable {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 发起方用户ID
     */
    private Long senderId;

    /**
     * 接收方用户ID
     */
    private Long receiverId;

    /**
     * 状态：0-待处理，1-已同意，2-已拒绝
     */
    private Integer status;

    /**
     * 申请时间
     */
    private Date createTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}