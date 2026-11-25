package com.zw.suser.controller;

import com.zw.scommon.api.CommonResult;
import com.zw.suser.application.user.UserAccountApplicationService;
import com.zw.suser.model.dto.UserLoginParam;
import com.zw.suser.model.dto.UserRegisterParam;
import com.zw.suser.model.vo.UserSearchVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口（RESTful，新前端使用）
 * 路径前缀: /auth
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserAccountApplicationService userAccountApplicationService;

    public AuthController(UserAccountApplicationService userAccountApplicationService) {
        this.userAccountApplicationService = userAccountApplicationService;
    }

    /**
     * 登录
     * POST /api/auth/login
     * Body: { "username": "xxx", "password": "xxx" }
     * 返回: { token, user: { id, username, nickname, avatar } }
     */
    @PostMapping("/login")
    public CommonResult<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        UserLoginParam param = new UserLoginParam();
        param.setAccountId(request.username());
        param.setPassword(request.password());

        Map<String, Object> loginResult = userAccountApplicationService.login(param);

        // 转换为新前端期望的格式
        Map<String, Object> user = new HashMap<>();
        user.put("id", loginResult.get("userId"));
        user.put("username", loginResult.get("accountId"));
        user.put("nickname", loginResult.get("nickname"));
        user.put("avatar", loginResult.containsKey("avatar") ? loginResult.get("avatar") : null);

        Map<String, Object> result = new HashMap<>();
        result.put("token", loginResult.get("tokenValue"));
        result.put("user", user);

        return CommonResult.success(result);
    }

    /**
     * 注册
     * POST /api/auth/register
     * Body: { "username": "xxx", "password": "xxx", "nickname": "xxx" }
     * 返回: { user: { id, username, nickname, avatar } }
     */
    @PostMapping("/register")
    public CommonResult<Map<String, Object>> register(@RequestBody @Valid RegisterRequest request) {
        UserRegisterParam param = new UserRegisterParam();
        param.setAccountId(request.username());
        param.setPassword(request.password());
        param.setNickname(request.nickname() != null ? request.nickname() : request.username());

        userAccountApplicationService.register(param);

        // 注册后查询用户信息返回
        UserSearchVo vo = userAccountApplicationService.searchByAccountId(request.username());
        Map<String, Object> user = new HashMap<>();
        user.put("id", vo.getId());
        user.put("username", vo.getAccountId());
        user.put("nickname", vo.getNickname());
        user.put("avatar", vo.getAvatar());

        Map<String, Object> result = new HashMap<>();
        result.put("user", user);
        return CommonResult.success(result);
    }

    /**
     * 登出
     * POST /api/auth/logout
     * Header: Authorization: Bearer <token>
     */
    @PostMapping("/logout")
    public CommonResult<String> logout() {
        userAccountApplicationService.logoutCurrent();
        return CommonResult.success("退出登录成功");
    }

    public record LoginRequest(
            @NotBlank(message = "账号不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    public record RegisterRequest(
            @NotBlank(message = "账号不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String nickname
    ) {
    }
}
