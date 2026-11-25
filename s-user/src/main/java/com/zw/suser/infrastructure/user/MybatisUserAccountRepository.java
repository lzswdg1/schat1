package com.zw.suser.infrastructure.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zw.suser.domain.user.model.UserAccount;
import com.zw.suser.domain.user.repository.UserAccountRepository;
import com.zw.suser.mapper.UserMapper;
import com.zw.suser.model.entity.User;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓储 MyBatis 实现。
 */
@Repository
public class MybatisUserAccountRepository implements UserAccountRepository {

    private final UserMapper userMapper;

    public MybatisUserAccountRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean existsByAccountId(String accountId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getAccountId, accountId);
        return userMapper.selectCount(wrapper) > 0;
    }

    @Override
    public Optional<UserAccount> findByAccountId(String accountId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getAccountId, accountId);
        return Optional.ofNullable(userMapper.selectOne(wrapper)).map(this::toDomain);
    }

    @Override
    public Optional<UserAccount> findById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(this::toDomain);
    }

    @Override
    public void save(UserAccount account) {
        User user = toEntity(account);
        userMapper.insert(user);
        account.markPersisted(user.getId());
    }

    @Override
    public void updateAvatar(Long userId, String avatarUrl) {
        User user = new User();
        user.setId(userId);
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);
    }

    private UserAccount toDomain(User user) {
        return UserAccount.restore(
                user.getId(),
                user.getAccountId(),
                user.getNickname(),
                user.getPassword(),
                user.getPublicKey(),
                user.getAvatar()
        );
    }

    private User toEntity(UserAccount account) {
        User user = new User();
        user.setId(account.getId());
        user.setAccountId(account.getAccountId());
        user.setNickname(account.getNickname());
        user.setPassword(account.getPasswordHash());
        user.setPublicKey(account.getPublicKey());
        user.setAvatar(account.getAvatar());
        return user;
    }
}
