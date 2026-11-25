package com.zw.suser.domain.user.service;

import com.zw.suser.domain.user.model.AuthToken;

/**
 * 登录态签发端口，隔离 Sa-Token/JWT 细节。
 */
public interface TokenIssuer {

    AuthToken issue(Long userId);

    Long currentUserId();

    void logoutCurrent();
}
