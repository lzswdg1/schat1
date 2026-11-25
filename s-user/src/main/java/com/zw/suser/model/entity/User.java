package com.zw.suser.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户凭证表
 * @TableName tb_user
 */
@TableName(value ="tb_user")
@Data
public class User implements Serializable {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 供他人搜索的唯一账号ID（如：zw123）
     */
    private String accountId;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 登录密码（BCrypt密文存储）
     */
    private String password;

    /**
     * 用户的RSA公钥（前端生成并上传）
     */
    private String publicKey;

    /**
     * 注册时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    private String avatar;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}