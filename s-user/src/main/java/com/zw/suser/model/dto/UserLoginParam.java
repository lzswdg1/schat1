package com.zw.suser.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 接收前端传来的登录参数
 */
@Data
public class UserLoginParam {

    @NotBlank(message = "账号不能为空")
    private String accountId;

    @NotBlank(message = "密码不能为空")
    private String password;
}
