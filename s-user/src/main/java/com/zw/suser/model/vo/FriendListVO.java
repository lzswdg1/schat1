package com.zw.suser.model.vo;

import lombok.Data;

@Data
public class FriendListVO {
    private Long id;          // 好友的用户ID
    private String accountId; // 搜索用的账号
    private String nickname;  // 昵称
    private String publicKey; // 预留给前端做 RSA 加密用的公钥
    // 用户头像直链
    private String avatar;
}