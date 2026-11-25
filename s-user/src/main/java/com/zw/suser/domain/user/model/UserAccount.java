package com.zw.suser.domain.user.model;

/**
 * 用户账号聚合根。
 * <p>
 * 领域对象只表达用户核心状态和规则，不直接依赖 MyBatis、Sa-Token 等技术细节。
 */
public class UserAccount {

    private Long id;
    private final String accountId;
    private String nickname;
    private String passwordHash;
    private String publicKey;
    private String avatar;

    private UserAccount(Long id,
                        String accountId,
                        String nickname,
                        String passwordHash,
                        String publicKey,
                        String avatar) {
        this.id = id;
        this.accountId = requireText(accountId, "账号不能为空");
        this.nickname = requireText(nickname, "昵称不能为空");
        this.passwordHash = requireText(passwordHash, "密码摘要不能为空");
        this.publicKey = publicKey;
        this.avatar = avatar;
    }

    public static UserAccount register(String accountId,
                                       String nickname,
                                       String passwordHash,
                                       String publicKey) {
        return new UserAccount(null, accountId, nickname, passwordHash, publicKey, null);
    }

    public static UserAccount restore(Long id,
                                      String accountId,
                                      String nickname,
                                      String passwordHash,
                                      String publicKey,
                                      String avatar) {
        return new UserAccount(id, accountId, nickname, passwordHash, publicKey, avatar);
    }

    public void markPersisted(Long id) {
        this.id = id;
    }

    public void changeAvatar(String avatarUrl) {
        this.avatar = requireText(avatarUrl, "头像链接不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getAvatar() {
        return avatar;
    }
}
