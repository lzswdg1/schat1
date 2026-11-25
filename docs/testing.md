# 测试与故障演练

## 默认测试

在根目录执行 `./mvnw test`；在 `web` 执行 `npm ci && npm test`，构建网页使用 `npm run build`。Node 工具测试在根目录执行 `node --test scripts/tests/*.test.mjs`。

测试使用合成身份与消息。默认后端测试不连接业务数据库；需要外部服务的测试通过环境变量启用。Node TCP 测试和慢 WebSocket 测试需要允许监听本机临时端口。

## 条件集成测试

仅使用新建、可清空的本机测试 MySQL 和 Redis。MySQL 数据库名称必须为 `schat_test`，测试夹具使用 `root` 和公开的专用测试密码 `schat-test-only`；不要将此密码用于应用或其他环境。测试会清理、迁移并写入这个测试库。

```sh
SCHAT_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/schat_test?useSSL=false&allowPublicKeyRetrieval=true' SCHAT_TEST_REDIS_PORT=16379 SCHAT_TEST_SLOW_WEBSOCKET=true ./mvnw test
```

上述端口仅为示例，必须替换为自行启动的独立测试实例。MySQL/Redis 不会由此命令自动创建。不要将这些变量指向业务实例。慢 WebSocket 测试自行启动临时 Tomcat，验证真实 TCP 回压与写入超时。

## 真实进程工具

`scripts/smoke-distributed-delivery.mjs` 使用 `schat_smoke` 测试库；`scripts/smoke-gateway.mjs` 使用 `schat_gateway_smoke`；负载工具使用 `schat_load`。脚本要求本机地址，并对库名或 Redis 状态设置保护，但使用前仍必须准备全新、独立、可丢弃的数据实例。

运行这些脚本前先按 README 复制示例配置并打包服务。通过各脚本的 `SCHAT_*_MYSQL_URL`、`SCHAT_*_REDIS_PORT` 和可选客户端路径变量指定环境。CLI 客户端默认从 PATH 查找 `mysql` 和 `redis-cli`。浏览器模式需要另行安装 Playwright 与 Chromium，不包含个人浏览器配置。

负载工具的帮助入口：`node scripts/load-distributed-delivery.mjs --help`。参数限制、样本量、客户端背压和机器资源都会影响结果；自行复测后解释结果，不将一次本机测试视为生产容量承诺。
