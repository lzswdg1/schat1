package com.zw.suser.model.vo;

import lombok.Data;

/**
 * 搜索用户时返回的安全视图对象 (脱敏，不含密码和公钥)
 */
@Data
public class UserSearchVo {
    private Long id;
    private String accountId;
    private String nickname;
    // 用户头像直链
    private String avatar;
}