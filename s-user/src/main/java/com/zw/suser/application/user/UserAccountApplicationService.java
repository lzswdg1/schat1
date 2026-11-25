package com.zw.suser.application.user;

import com.zw.scommon.exception.ApiException;
import com.zw.suser.domain.user.model.AuthToken;
import com.zw.suser.domain.user.model.UserAccount;
import com.zw.suser.domain.user.repository.UserAccountRepository;
import com.zw.suser.domain.user.service.PasswordHasher;
import com.zw.suser.domain.user.service.TokenIssuer;
import com.zw.suser.model.dto.UserLoginParam;
import com.zw.suser.model.dto.UserRegisterParam;
import com.zw.suser.model.vo.UserSearchVo;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户应用服务：编排注册、登录、搜索、资料更新等用例。
 */
@Service
public class UserAccountApplicationService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;

    public UserAccountApplicationService(UserAccountRepository userAccountRepository,
                                         PasswordHasher passwordHasher,
                                         TokenIssuer tokenIssuer) {
        this.userAccountRepository = userAccountRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
    }

    public void register(UserRegisterParam param) {
        if (userAccountRepository.existsByAccountId(param.getAccountId())) {
            throw new ApiException("该账号已被注册，请更换一个");
        }

        UserAccount account = UserAccount.register(
                param.getAccountId(),
                param.getNickname(),
                passwordHasher.hash(param.getPassword()),
                param.getPublicKey()
        );
        userAccountRepository.save(account);
    }

    public Map<String, Object> login(UserLoginParam param) {
        UserAccount account = userAccountRepository.findByAccountId(param.getAccountId())
                .orElseThrow(() -> new ApiException("账号或密码错误"));

        if (!passwordHasher.matches(param.getPassword(), account.getPasswordHash())) {
            throw new ApiException("账号或密码错误");
        }

        AuthToken token = tokenIssuer.issue(account.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("tokenName", token.getTokenName());
        result.put("tokenValue", token.getTokenValue());
        result.put("userId", account.getId());
        result.put("accountId", account.getAccountId());
        result.put("nickname", account.getNickname());
        result.put("avatar", account.getAvatar());
        result.put("publicKey", account.getPublicKey());
        return result;
    }

    public UserSearchVo searchByAccountId(String accountId) {
        UserAccount account = userAccountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ApiException("未找到该用户"));

        UserSearchVo vo = new UserSearchVo();
        vo.setId(account.getId());
        vo.setAccountId(account.getAccountId());
        vo.setNickname(account.getNickname());
        vo.setAvatar(account.getAvatar());
        return vo;
    }

    public void updateAvatar(String avatarUrl) {
        Long userId = tokenIssuer.currentUserId();
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ApiException("当前用户不存在"));
        account.changeAvatar(avatarUrl);
        userAccountRepository.updateAvatar(userId, account.getAvatar());
    }

    public void logoutCurrent() {
        tokenIssuer.logoutCurrent();
    }
}
