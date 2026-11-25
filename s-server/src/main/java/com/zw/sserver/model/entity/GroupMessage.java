package com.zw.sserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 群聊消息表（读扩散模型：消息只存一份，每个成员维护已读游标）
 */
@TableName("tb_group_message")
@Data
public class GroupMessage implements Serializable {

    /** 全局唯一消息 ID（雪花算法） */
    @TableId(type = IdType.INPUT)
    private Long messageId;

    private Long groupId;
    private Long senderId;

    /** 客户端防重 ID */
    private String clientMsgId;

    /** 消息内容（加密后的 JSON） */
    private String cipherContent;

    private Date sendTime;

    /** 引用消息 ID */
    private Long quoteMsgId;
    private String quoteContent;

    /** 群会话级递增序号 */
    private Long seq;

    private static final long serialVersionUID = 1L;
}
