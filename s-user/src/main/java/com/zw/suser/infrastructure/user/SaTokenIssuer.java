package com.zw.suser.infrastructure.user;

import cn.dev33.satoken.stp.StpUtil;
import com.zw.suser.domain.user.model.AuthToken;
import com.zw.suser.domain.user.service.TokenIssuer;
import org.springframework.stereotype.Component;

/**
 * Sa-Token/JWT 登录态适配器。
 */
@Component
public class SaTokenIssuer implements TokenIssuer {

    @Override
    public AuthToken issue(Long userId) {
        StpUtil.login(userId);
        return new AuthToken(StpUtil.getTokenName(), StpUtil.getTokenValue());
    }

    @Override
    public Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    @Override
    public void logoutCurrent() {
        StpUtil.logout();
    }
}
