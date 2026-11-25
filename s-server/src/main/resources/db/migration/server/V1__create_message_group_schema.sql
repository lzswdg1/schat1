-- Message and group schema managed by Flyway.

CREATE TABLE IF NOT EXISTS `tb_chat_message` (
    `message_id` bigint(20) NOT NULL COMMENT '全局唯一消息ID（雪花算法生成）',
    `client_msg_id` varchar(64) NOT NULL COMMENT '前端生成的唯一消息ID，用于并发防重发',
    `sender_id` bigint(20) NOT NULL COMMENT '发送方ID',
    `receiver_id` bigint(20) NOT NULL COMMENT '接收方ID',
    `cipher_content` text NOT NULL COMMENT '聊天报文（含消息体 JSON）',
    `quote_msg_id` bigint(20) DEFAULT NULL COMMENT '引用的消息ID（无引用则为空）',
    `quote_content` varchar(255) DEFAULT NULL COMMENT '引用的消息内容摘要',
    `is_read` tinyint(1) NOT NULL DEFAULT '0' COMMENT '消息状态：0-未送达，1-已送达，2-已读',
    `seq` bigint(20) DEFAULT NULL COMMENT '会话级递增序号（Redis INCR 生成，用于增量同步）',
    `send_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`message_id`),
    UNIQUE KEY `uk_sender_client_msg` (`sender_id`, `client_msg_id`) COMMENT '防并发重复插入',
    KEY `idx_receiver_read_time` (`receiver_id`, `is_read`, `send_time`) COMMENT '加速上线拉未读消息',
    KEY `idx_sender_receiver_msg` (`sender_id`, `receiver_id`, `message_id`) COMMENT '加速游标分页 (A→B)',
    KEY `idx_receiver_sender_msg` (`receiver_id`, `sender_id`, `message_id`) COMMENT '加速游标分页 (B→A)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私聊消息表';

CREATE TABLE IF NOT EXISTS `tb_group_info` (
    `group_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '群ID',
    `group_name` varchar(50) NOT NULL COMMENT '群名称',
    `owner_id` bigint(20) NOT NULL COMMENT '群主用户ID',
    `avatar` varchar(255) DEFAULT '' COMMENT '群头像URL',
    `notice` varchar(500) DEFAULT NULL COMMENT '群公告',
    `max_members` int(11) NOT NULL DEFAULT '1000' COMMENT '群成员上限',
    `member_count` int(11) NOT NULL DEFAULT '0' COMMENT '当前成员数',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`group_id`),
    KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群组信息表';

CREATE TABLE IF NOT EXISTS `tb_group_member` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `group_id` bigint(20) NOT NULL COMMENT '群ID',
    `user_id` bigint(20) NOT NULL COMMENT '用户ID',
    `role` varchar(10) NOT NULL DEFAULT 'MEMBER' COMMENT '角色：OWNER/ADMIN/MEMBER',
    `nickname` varchar(50) DEFAULT NULL COMMENT '群内昵称（可选）',
    `muted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否免打扰：0-否，1-是',
    `join_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`) COMMENT '防止重复加入',
    KEY `idx_user_id` (`user_id`) COMMENT '加速查询用户加入的群'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群成员表';

CREATE TABLE IF NOT EXISTS `tb_group_message` (
    `message_id` bigint(20) NOT NULL COMMENT '全局唯一消息ID（雪花算法生成）',
    `group_id` bigint(20) NOT NULL COMMENT '群ID',
    `sender_id` bigint(20) NOT NULL COMMENT '发送方ID',
    `client_msg_id` varchar(64) NOT NULL COMMENT '客户端防重ID',
    `cipher_content` text COMMENT '消息内容（JSON）',
    `quote_msg_id` bigint(20) DEFAULT NULL COMMENT '引用消息ID',
    `quote_content` varchar(255) DEFAULT NULL COMMENT '引用消息内容摘要',
    `seq` bigint(20) DEFAULT NULL COMMENT '群会话级递增序号',
    `send_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`message_id`),
    KEY `idx_group_msg` (`group_id`, `message_id`) COMMENT '加速群消息游标分页',
    UNIQUE KEY `uk_group_sender_client` (`group_id`, `sender_id`, `client_msg_id`) COMMENT '防重唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊消息表（读扩散模型）';

CREATE TABLE IF NOT EXISTS `tb_message_consume_log` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id` bigint(20) NOT NULL COMMENT '消息ID',
    `message_type` varchar(32) NOT NULL COMMENT '消息类型：PRIVATE/GROUP',
    `consumer_group` varchar(128) NOT NULL COMMENT '消费者组',
    `status` varchar(32) NOT NULL COMMENT '消费状态：SUCCESS/DUPLICATE/FAILURE',
    `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '消费/更新次数',
    `fail_reason` varchar(512) DEFAULT NULL COMMENT '失败原因',
    `consume_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '最近消费时间',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_consumer` (`message_id`, `consumer_group`) COMMENT '同一消费组消费同一消息只保留一条状态',
    KEY `idx_status_time` (`status`, `consume_time`) COMMENT '按状态排查消费异常'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息消费状态日志表';
