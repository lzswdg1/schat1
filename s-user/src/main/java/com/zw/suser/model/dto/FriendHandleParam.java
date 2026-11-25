package com.zw.suser.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FriendHandleParam {

    @NotNull(message = "申请ID不能为空")
    private Long applyId;

    @NotNull(message = "处理状态不能为空")
    private Integer status;
}
