-- User service schema managed by Flyway.

CREATE TABLE IF NOT EXISTS `tb_user` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `account_id` varchar(50) NOT NULL COMMENT '供他人搜索的唯一账号ID（如：zw123）',
    `nickname` varchar(50) NOT NULL COMMENT '用户昵称',
    `avatar` varchar(255) DEFAULT '' COMMENT '用户头像直链',
    `password` varchar(255) NOT NULL COMMENT '登录密码（BCrypt密文存储）',
    `public_key` text COMMENT '用户的RSA公钥（前端生成并上传，预留端到端加密）',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_id` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户凭证与基础信息表';

CREATE TABLE IF NOT EXISTS `tb_friend_request` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `sender_id` bigint(20) NOT NULL COMMENT '发起方用户ID',
    `receiver_id` bigint(20) NOT NULL COMMENT '接收方用户ID',
    `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态：0-待处理，1-已同意，2-已拒绝',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    KEY `idx_receiver_id` (`receiver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友申请流转表';

CREATE TABLE IF NOT EXISTS `tb_friend_relation` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id_1` bigint(20) NOT NULL COMMENT '用户1 ID',
    `user_id_2` bigint(20) NOT NULL COMMENT '用户2 ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '成为好友的时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_relation` (`user_id_1`, `user_id_2`) COMMENT '联合唯一索引，防止关系重复'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友关系双向关联表';
