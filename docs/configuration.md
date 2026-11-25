# 示例配置与部署说明

仓库中的 `application.example.yaml`、`.env.example` 和 `compose.example.yaml` 是脱敏的本地开发模板。它们不包含可复用密码或真实部署地址。Spring Boot 不会自动加载 `application.example.yaml`，应先复制成同目录的 `application.yaml`，再构建应用。

## 配置来源

服务模板从项目根目录可选导入 `.env`，环境变量也可以提供对应值。示例仅监听 `127.0.0.1`。网关使用直接 HTTP/WebSocket 地址，用户服务使用 SimpleDiscoveryClient 查找本机聊天服务。更改服务端口时保持路由和服务发现值一致。Vite 开发代理默认连接 `http://127.0.0.1:8080`。如需使用另一网关地址，在启动 Vite 的进程中设置 `SCHAT_WEB_GATEWAY_URL`。

| 变量 | 用途 |
| --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | 自行提供的开发数据库地址 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 应用数据库身份与密码 |
| `MYSQL_ROOT_PASSWORD` | 仅供示例 MySQL 容器初始化 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接信息 |
| `SCHAT_JWT_SECRET` | 各服务一致使用的 JWT 密钥 |
| `SCHAT_INTERNAL_SECRET` | 用户与聊天服务之间的内部调用密钥 |
| `SCHAT_INSTANCE_ID` | 聊天节点的稳定且唯一标识 |
| `SCHAT_LOGIN_TRUSTED_PROXIES` | 可选的可信代理精确 IP 列表 |

代理列表默认留空。使用网关时，登录服务可能把网关视为共同来源；需要按实际代理链配置精确地址，并确保代理覆盖或正确追加转发头。不要信任所有客户端提供的 `X-Forwarded-For`。

## 密钥与日志

所有密码和密钥都由使用者生成并保存在本地或部署平台的密钥管理系统中。JWT 与内部密钥应不同。可选运维和告警也各用独立密钥。配置示例不设可复用密码回退值。

示例关闭完整 SQL 输出，并使用 INFO 级别日志。应避免网关、代理和访问日志记录 WebSocket 查询参数中的令牌及消息正文。日志和上传文件的访问权限、轮转与保留策略由部署方设置。

## 数据库与基础设施

Flyway 按用户服务和聊天服务分开管理迁移及历史表；应先备份已有数据库，再评估迁移。不要把业务数据库导出、账号记录或消息样本提交到源码仓库。

示例 Compose 只启动 MySQL 和 Redis，端口仅发布到本机回环地址，持久化使用独立 Docker 卷。删除卷会删除开发数据，请自行管理。旧异步队列持久化开关保持关闭，当前发送路径使用数据库 Outbox。

## 生产环境

本地模板不是生产部署配置。应配置 TLS、网络隔离、数据库最小权限、Redis 容量和持久化、备份与恢复、监控和依赖安全更新。运维接口、文档端点与上传文件需要符合自身访问策略。多个聊天实例必须使用不同且重启后稳定的实例标识。
