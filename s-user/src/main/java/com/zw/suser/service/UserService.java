package com.zw.suser.service;

import com.zw.suser.model.dto.UserLoginParam;
import com.zw.suser.model.dto.UserRegisterParam;
import com.zw.suser.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zw.suser.model.vo.UserSearchVo;

import java.util.Map;

/**
* @description 针对表【tb_user(用户凭证表)】的数据库操作Service
* @createDate 2026-02-25 20:44:05
*/
public interface UserService extends IService<User> {
    /**
     * 用户注册 (包含密码 BCrypt 加密与公钥落库)
     */
    void register(UserRegisterParam param);

    /**
     * 用户登录 (校验密码并签发 Sa-Token)
     * @return 包含 Token 和 用户基础信息的 Map
     */
    Map<String, Object> login(UserLoginParam param);

    /**
     * 精确搜索目标用户 (发起加好友的前提)
     * @param accountId 对方的唯一账号ID
     * @return 脱敏后的用户信息
     */
    UserSearchVo searchByAccountId(String accountId);

    void updateAvatar(String avatarUrl);

    void logout();
}
