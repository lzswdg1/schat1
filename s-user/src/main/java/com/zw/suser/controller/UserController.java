package com.zw.suser.controller;

import com.zw.scommon.api.CommonResult;
import com.zw.suser.application.user.UserAccountApplicationService;
import com.zw.suser.model.vo.UserSearchVo;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 当前前端使用的用户资料接口。
 */
@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserAccountApplicationService userAccountApplicationService;

    public UserController(UserAccountApplicationService userAccountApplicationService) {
        this.userAccountApplicationService = userAccountApplicationService;
    }

    /**
     * 搜索目标用户接口 (发起加好友前调用)
     * @param accountId 对方的唯一账号ID
     * @return 目标用户的脱敏信息
     */
    @GetMapping("/search")
    public CommonResult<UserSearchVo> search(@RequestParam("accountId") @NotBlank(message = "账号不能为空") String accountId) {
        UserSearchVo vo = userAccountApplicationService.searchByAccountId(accountId);
        return CommonResult.success(vo);
    }

    /**
     * 更新用户头像
     * POST /api/user/updateAvatar
     */
    @PostMapping("/updateAvatar")
    public CommonResult<String> updateAvatar(@RequestParam("avatarUrl") @NotBlank(message = "头像链接不能为空") String avatarUrl) {
        userAccountApplicationService.updateAvatar(avatarUrl);
        return CommonResult.success("头像更新成功");
    }
}
