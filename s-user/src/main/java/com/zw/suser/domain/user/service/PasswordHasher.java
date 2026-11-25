package com.zw.suser.domain.user.service;

/**
 * 密码哈希端口，领域和应用层不直接绑定 BCrypt 实现。
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
