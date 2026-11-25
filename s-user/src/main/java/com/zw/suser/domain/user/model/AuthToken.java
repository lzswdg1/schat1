package com.zw.suser.domain.user.model;

/**
 * 认证令牌值对象，屏蔽具体 Token 组件的实现细节。
 */
public class AuthToken {

    private final String tokenName;
    private final String tokenValue;

    public AuthToken(String tokenName, String tokenValue) {
        this.tokenName = tokenName;
        this.tokenValue = tokenValue;
    }

    public String getTokenName() {
        return tokenName;
    }

    public String getTokenValue() {
        return tokenValue;
    }
}
