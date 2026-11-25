package com.zw.suser.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FriendApplyParam {

    @NotBlank(message = "目标账号不能为空")
    private String targetAccountId;

    private String applyReason;
}
