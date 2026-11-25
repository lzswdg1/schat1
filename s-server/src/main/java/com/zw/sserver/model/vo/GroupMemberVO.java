package com.zw.sserver.model.vo;

import lombok.Data;

/**
 * 群成员视图对象（脱敏，不暴露 accountId）
 */
@Data
public class GroupMemberVO {
    private Long userId;
    private String nickname;
    private String avatar;
    private String role;
    private Integer muted;
}
