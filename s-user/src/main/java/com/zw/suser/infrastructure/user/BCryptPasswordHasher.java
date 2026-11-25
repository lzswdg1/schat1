package com.zw.suser.infrastructure.user;

import cn.dev33.satoken.secure.BCrypt;
import com.zw.suser.domain.user.service.PasswordHasher;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密码哈希适配器。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    @Override
    public String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return BCrypt.checkpw(rawPassword, passwordHash);
    }
}
