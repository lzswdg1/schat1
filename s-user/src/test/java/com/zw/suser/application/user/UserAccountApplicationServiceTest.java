package com.zw.suser.application.user;

import com.zw.scommon.exception.ApiException;
import com.zw.suser.domain.user.model.AuthToken;
import com.zw.suser.domain.user.model.UserAccount;
import com.zw.suser.domain.user.repository.UserAccountRepository;
import com.zw.suser.domain.user.service.PasswordHasher;
import com.zw.suser.domain.user.service.TokenIssuer;
import com.zw.suser.model.dto.UserLoginParam;
import com.zw.suser.model.dto.UserRegisterParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAccountApplicationServiceTest {

    private final InMemoryUserAccountRepository repository = new InMemoryUserAccountRepository();
    private final PrefixPasswordHasher passwordHasher = new PrefixPasswordHasher();
    private final FixedTokenIssuer tokenIssuer = new FixedTokenIssuer();
    private final UserAccountApplicationService service =
            new UserAccountApplicationService(repository, passwordHasher, tokenIssuer);

    @Test
    void registerHashesPasswordAndPersistsAccount() {
        UserRegisterParam param = registerParam("zw123", "Zhang Wei", "plain-password");

        service.register(param);

        UserAccount account = repository.findByAccountId("zw123").orElseThrow();
        assertEquals("zw123", account.getAccountId());
        assertEquals("Zhang Wei", account.getNickname());
        assertEquals("public-key", account.getPublicKey());
        assertNotEquals("plain-password", account.getPasswordHash());
        assertTrue(account.getPasswordHash().startsWith("hashed:"));
    }

    @Test
    void registerRejectsDuplicateAccountId() {
        service.register(registerParam("zw123", "Zhang Wei", "plain-password"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.register(registerParam("zw123", "Another", "plain-password")));

        assertEquals("该账号已被注册，请更换一个", exception.getMessage());
    }

    @Test
    void loginReturnsJwtEnvelopeAndUserProfile() {
        service.register(registerParam("zw123", "Zhang Wei", "plain-password"));

        Map<String, Object> result = service.login(loginParam("zw123", "plain-password"));

        assertEquals("Authorization", result.get("tokenName"));
        assertEquals("Bearer mock-token", result.get("tokenValue"));
        assertEquals("zw123", result.get("accountId"));
        assertEquals("Zhang Wei", result.get("nickname"));
        assertEquals(1L, result.get("userId"));
    }

    @Test
    void loginRejectsWrongPassword() {
        service.register(registerParam("zw123", "Zhang Wei", "plain-password"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.login(loginParam("zw123", "wrong-password")));

        assertEquals("账号或密码错误", exception.getMessage());
    }

    private static UserRegisterParam registerParam(String accountId, String nickname, String password) {
        UserRegisterParam param = new UserRegisterParam();
        param.setAccountId(accountId);
        param.setNickname(nickname);
        param.setPassword(password);
        param.setPublicKey("public-key");
        return param;
    }

    private static UserLoginParam loginParam(String accountId, String password) {
        UserLoginParam param = new UserLoginParam();
        param.setAccountId(accountId);
        param.setPassword(password);
        return param;
    }

    private static class InMemoryUserAccountRepository implements UserAccountRepository {
        private final Map<String, UserAccount> byAccountId = new HashMap<>();
        private final Map<Long, UserAccount> byId = new HashMap<>();
        private long nextId = 1L;

        @Override
        public boolean existsByAccountId(String accountId) {
            return byAccountId.containsKey(accountId);
        }

        @Override
        public Optional<UserAccount> findByAccountId(String accountId) {
            return Optional.ofNullable(byAccountId.get(accountId));
        }

        @Override
        public Optional<UserAccount> findById(Long userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public void save(UserAccount account) {
            account.markPersisted(nextId++);
            byAccountId.put(account.getAccountId(), account);
            byId.put(account.getId(), account);
        }

        @Override
        public void updateAvatar(Long userId, String avatarUrl) {
            // Not needed by these use-case tests.
        }
    }

    private static class PrefixPasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return hash(rawPassword).equals(passwordHash);
        }
    }

    private static class FixedTokenIssuer implements TokenIssuer {
        @Override
        public AuthToken issue(Long userId) {
            return new AuthToken("Authorization", "Bearer mock-token");
        }

        @Override
        public Long currentUserId() {
            return 1L;
        }

        @Override
        public void logoutCurrent() {
            // Not needed by these use-case tests.
        }
    }
}
