package com.zw.sserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 离线加密消息表
 * @TableName tb_chat_message
 */
@TableName(value ="tb_chat_message")
@Data
public class ChatMessage implements Serializable {
    /**
     * 全局唯一消息ID（雪花算法生成）
     */
    @TableId(type = IdType.INPUT)
    private Long messageId;

    /**
     * 发送方ID
     */
    private Long senderId;

    /**
     * 接收方ID
     */
    private Long receiverId;

    /**
     * 加密后的聊天报文（含AES信封与密文）
     */
    private String cipherContent;

    /**
     * 消息状态：0-未送达，1-已送达，2-已读
     */
    private Integer isRead;

    /**
     * 发送时间
     */
    private Date sendTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    /**
     * 前端生成的唯一消息ID
     */
    @TableField("client_msg_id")
    private String clientMsgId;


    // 引用的消息ID
    private Long quoteMsgId;
    // 引用的消息内容摘要
    private String quoteContent;

    /**
     * 会话级递增序号（per-conversation sequence）
     * 用于客户端增量同步：拉取 seq > lastSeq 的消息即可获取所有新消息。
     * 与消息写入在同一 MySQL 事务中分配。
     */
    private Long seq;

}
