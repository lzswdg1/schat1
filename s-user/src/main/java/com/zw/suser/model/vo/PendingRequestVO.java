package com.zw.suser.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Date;

@Data
public class PendingRequestVO {
    /**
     * 申请记录主键 ID
     * 后端字段 applyId，JSON 序列化时同时输出 "id" 给新前端
     */
    @JsonProperty("id")
    private Long applyId;

    /**
     * 发起人用户 ID
     * JSON 序列化时输出 "fromUserId" 给新前端
     */
    @JsonProperty("fromUserId")
    private Long senderId;

    /**
     * 发起人昵称
     * JSON 序列化时输出 "fromUserName" 给新前端
     */
    @JsonProperty("fromUserName")
    private String applicantNickname;

    /**
     * 发起人头像
     * JSON 序列化时输出 "fromUserAvatar" 给新前端
     */
    @JsonProperty("fromUserAvatar")
    private String applicantAvatar;

    /**
     * 发起人账号（保留，前端可选用）
     */
    private String applicantAccountId;

    /**
     * 申请时间
     */
    private Date createTime;
}
