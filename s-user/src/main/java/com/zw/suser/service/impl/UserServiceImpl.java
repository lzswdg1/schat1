package com.zw.suser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zw.suser.application.user.UserAccountApplicationService;
import com.zw.suser.mapper.UserMapper;
import com.zw.suser.model.dto.UserLoginParam;
import com.zw.suser.model.dto.UserRegisterParam;
import com.zw.suser.model.entity.User;
import com.zw.suser.model.vo.UserSearchVo;
import com.zw.suser.service.UserService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
* @description 针对表【tb_user(用户凭证表)】的数据库操作Service实现
* @createDate 2026-02-25 20:44:05
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    private final UserAccountApplicationService userAccountApplicationService;

    public UserServiceImpl(UserAccountApplicationService userAccountApplicationService) {
        this.userAccountApplicationService = userAccountApplicationService;
    }

    @Override
    public void register(UserRegisterParam param) {
        userAccountApplicationService.register(param);
    }

    @Override
    public Map<String, Object> login(UserLoginParam param) {
        return userAccountApplicationService.login(param);
    }

    @Override
    public UserSearchVo searchByAccountId(String accountId) {
        return userAccountApplicationService.searchByAccountId(accountId);
    }

    @Override
    public void updateAvatar(String avatarUrl) {
        userAccountApplicationService.updateAvatar(avatarUrl);
    }

    @Override
    public void logout() {
        userAccountApplicationService.logoutCurrent();
    }
}


