package com.zw.suser.domain.user.repository;

import com.zw.suser.domain.user.model.UserAccount;

import java.util.Optional;

/**
 * 用户聚合仓储端口，由基础设施层负责接入 MyBatis/MySQL。
 */
public interface UserAccountRepository {

    boolean existsByAccountId(String accountId);

    Optional<UserAccount> findByAccountId(String accountId);

    Optional<UserAccount> findById(Long userId);

    void save(UserAccount account);

    void updateAvatar(Long userId, String avatarUrl);
}
