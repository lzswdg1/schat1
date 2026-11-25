package com.zw.sserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * MQ 消费状态日志。
 */
@TableName("tb_message_consume_log")
@Data
public class MessageConsumeLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long messageId;
    private String messageType;
    private String consumerGroup;
    private String status;
    private Integer retryCount;
    private String failReason;
    private Date consumeTime;
    private Date createTime;
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
