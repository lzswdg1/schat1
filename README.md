# SChat

[简体中文](#中文) · [日本語](#日本語) · [English](#english)

## 中文

SChat 是一个基于 Java、Spring Boot 和 Vue 3 的即时通信项目。它支持账号注册与登录、好友关系、私聊和群聊，以及图片上传、消息搜索、撤回和重连后的消息同步。

### 消息如何送达

- **发送确认**。消息、会话序号和接收方投递任务在同一个 MySQL 事务中提交，服务端随后确认发送。
- **投递确认**。后台任务通过 WebSocket 和 Redis 路由消息。网页写入 IndexedDB 后才确认投递，重连时可以重试未确认的任务。已读状态单独处理。
- **历史同步**。客户端按会话同步和分页。重复消息会合并，迟到的旧版本消息不会覆盖已撤回的状态。

| 模块 | 职责 |
| --- | --- |
| `s-gateway` | HTTP 和 WebSocket 路由、入口认证 |
| `s-user` | 注册、登录、好友关系 |
| `s-server` | 会话、群组、消息、投递任务和确认 |
| `s-file` | 图片上传与读取 |
| `s-common` | 公共响应、异常和限流组件 |
| `web` | Vue 3 网页客户端和 IndexedDB 缓存 |
| `scripts` | 本地集成验证与负载测试工具 |

### 本地运行

安装 JDK 17 或更新版本、Node.js 22 和 npm。以下命令使用 Docker Compose 启动本地 MySQL 8 和 Redis。已有独立开发实例时，可以填写其连接信息并跳过 Compose。

在仓库根目录复制配置模板。

```sh
cp .env.example .env
cp compose.example.yaml compose.yaml
for service in s-user s-server s-file s-gateway; do
  cp "$service/src/main/resources/application.example.yaml" \
     "$service/src/main/resources/application.yaml"
done
```

在 `.env` 填写 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`SCHAT_JWT_SECRET` 和 `SCHAT_INTERNAL_SECRET`。可以用 `openssl rand -hex 32` 为每项分别生成随机值。`.env` 和复制后的服务配置都应留在本地。

```sh
docker compose --env-file .env -f compose.yaml up -d --wait
./mvnw -DskipTests package
```

在四个终端中分别执行以下命令，均从仓库根目录启动。Flyway 会在服务启动时迁移数据库。

```sh
java -jar s-user/target/s-user-0.0.1-SNAPSHOT.jar
java -jar s-server/target/s-server-0.0.1-SNAPSHOT.jar
java -jar s-file/target/s-file-0.0.1-SNAPSHOT.jar
java -jar s-gateway/target/s-gateway-0.0.1-SNAPSHOT.jar
```

在另一个终端启动网页，打开 Vite 显示的地址并注册测试账号。仅预览界面时，可运行 `VITE_USE_MOCK=true npm run dev`，使用合成数据跳过后端启动。

```sh
cd web
npm ci
npm run dev
```

### 测试、部署与许可证

在根目录运行 `./mvnw test` 和 `node --test scripts/tests/*.test.mjs`，在 `web` 中运行 `npm test` 和 `npm run build`。需要 MySQL 或 Redis 的集成测试使用独立测试实例。详见[测试说明](docs/testing.md)。

示例配置用于本地开发。服务端可读取消息正文。部署相关的网络和凭据设置参见[配置说明](docs/configuration.md)。

项目采用 [Apache License 2.0](LICENSE)。第三方组件保留各自的许可证，详见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 日本語

SChat は、Java、Spring Boot、Vue 3 を使ったインスタントメッセージングプロジェクトです。アカウント登録とログイン、フレンド管理、1 対 1 のチャット、グループチャット、画像のアップロード、メッセージ検索、送信取り消し、再接続後の同期に対応しています。

### メッセージ配送の仕組み

- **送信確認**。メッセージ、会話ごとの連番、受信者への配送タスクを同じ MySQL トランザクションで保存し、コミット後にサーバーが送信を確認します。
- **配送確認**。バックグラウンド処理が WebSocket と Redis 経由でメッセージを配送します。Web クライアントは IndexedDB への保存後に配送を確認します。未確認の配送は再接続時に再試行でき、既読状態は別に管理します。
- **履歴の同期**。クライアントは会話ごとに履歴を同期し、ページ単位で読み込みます。重複するメッセージを統合し、遅れて到着した古いメッセージで送信取り消しの状態を上書きしません。

| モジュール | 役割 |
| --- | --- |
| `s-gateway` | HTTP と WebSocket のルーティング、入口での認証 |
| `s-user` | アカウント登録、ログイン、フレンド管理 |
| `s-server` | 会話、グループ、メッセージ、配送タスクと配送確認 |
| `s-file` | 画像のアップロードと取得 |
| `s-common` | 共通レスポンス、例外、レート制限のコンポーネント |
| `web` | Vue 3 の Web クライアントと IndexedDB キャッシュ |
| `scripts` | ローカルの結合テストと負荷テスト用ツール |

### ローカルで実行する

JDK 17 以降、Node.js 22、npm を用意します。以下の手順では Docker Compose でローカルの MySQL 8 と Redis を起動します。専用の開発用インスタンスがあれば、その接続情報を設定して Compose の起動を省略できます。

リポジトリのルートで設定テンプレートをコピーします。

```sh
cp .env.example .env
cp compose.example.yaml compose.yaml
for service in s-user s-server s-file s-gateway; do
  cp "$service/src/main/resources/application.example.yaml" \
     "$service/src/main/resources/application.yaml"
done
```

`.env` の `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`SCHAT_JWT_SECRET`、`SCHAT_INTERNAL_SECRET` を設定します。`openssl rand -hex 32` などで項目ごとに異なる値を生成できます。`.env` とコピーしたサービス設定はローカルで管理します。

```sh
docker compose --env-file .env -f compose.yaml up -d --wait
./mvnw -DskipTests package
```

リポジトリのルートから、次の 4 つのコマンドをそれぞれ別のターミナルで実行します。サービス起動時に Flyway がデータベースをマイグレーションします。

```sh
java -jar s-user/target/s-user-0.0.1-SNAPSHOT.jar
java -jar s-server/target/s-server-0.0.1-SNAPSHOT.jar
java -jar s-file/target/s-file-0.0.1-SNAPSHOT.jar
java -jar s-gateway/target/s-gateway-0.0.1-SNAPSHOT.jar
```

別のターミナルで Web クライアントを起動します。Vite が表示する URL を開き、テスト用アカウントを登録してください。画面だけを試す場合は、`VITE_USE_MOCK=true npm run dev` でバックエンドを起動せずにサンプルデータを使えます。

```sh
cd web
npm ci
npm run dev
```

### テスト、デプロイ、ライセンス

ルートで `./mvnw test` と `node --test scripts/tests/*.test.mjs` を実行します。`web` では `npm test` と `npm run build` を実行します。MySQL や Redis が必要な結合テストには専用のインスタンスを使います。詳細は[テストガイド（中国語）](docs/testing.md)を参照してください。

設定例はローカル開発向けです。サーバーはメッセージ本文を読み取れます。デプロイ時のネットワークと認証情報については[設定ガイド（中国語）](docs/configuration.md)を参照してください。

プロジェクトには [Apache License 2.0](LICENSE) が適用されます。サードパーティ製コンポーネントのライセンスについては[サードパーティの通知](THIRD_PARTY_NOTICES.md)を参照してください。

## English

SChat is an instant messaging project built with Java, Spring Boot, and Vue 3. It supports account registration and sign-in, contacts, direct and group chat, image uploads, message search, recall, and synchronization after reconnecting.

### How delivery works

- **Send acknowledgment.** A single MySQL transaction stores the message, its conversation sequence number, and the recipient's delivery task. The server acknowledges the send after commit.
- **Delivery acknowledgment.** Background workers route messages over WebSocket and Redis. The web client acknowledges delivery after saving to IndexedDB. Pending deliveries can be retried on reconnect. Read receipts are tracked separately.
- **History synchronization.** The client synchronizes and paginates history by conversation. It merges duplicates and preserves recalled state when an older version arrives late.

| Module | Responsibility |
| --- | --- |
| `s-gateway` | HTTP and WebSocket routing, gateway authentication |
| `s-user` | Account registration, sign-in, and contacts |
| `s-server` | Conversations, groups, messaging, delivery tasks, and acknowledgments |
| `s-file` | Image upload and retrieval |
| `s-common` | Shared responses, exceptions, and rate limiting |
| `web` | Vue 3 web client and IndexedDB cache |
| `scripts` | Local integration and load testing tools |

### Run locally

Install JDK 17 or later, Node.js 22, and npm. The commands below use Docker Compose to start local MySQL 8 and Redis. If you already have dedicated development instances, configure those connections and skip the Compose command.

From the repository root, copy the configuration templates.

```sh
cp .env.example .env
cp compose.example.yaml compose.yaml
for service in s-user s-server s-file s-gateway; do
  cp "$service/src/main/resources/application.example.yaml" \
     "$service/src/main/resources/application.yaml"
done
```

Set `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `REDIS_PASSWORD`, `SCHAT_JWT_SECRET`, and `SCHAT_INTERNAL_SECRET` in `.env`. Use a separate random value for each field, for example by running `openssl rand -hex 32` each time. Keep `.env` and the copied service configuration files local.

```sh
docker compose --env-file .env -f compose.yaml up -d --wait
./mvnw -DskipTests package
```

Start each service in its own terminal from the repository root. Flyway applies database migrations when the services start.

```sh
java -jar s-user/target/s-user-0.0.1-SNAPSHOT.jar
java -jar s-server/target/s-server-0.0.1-SNAPSHOT.jar
java -jar s-file/target/s-file-0.0.1-SNAPSHOT.jar
java -jar s-gateway/target/s-gateway-0.0.1-SNAPSHOT.jar
```

Start the web client in another terminal, open the URL printed by Vite, and register a test account. For a UI preview with synthetic data, run `VITE_USE_MOCK=true npm run dev` without starting the backend.

```sh
cd web
npm ci
npm run dev
```

### Testing, deployment, and license

From the root, run `./mvnw test` and `node --test scripts/tests/*.test.mjs`. In `web`, run `npm test` and `npm run build`. Use dedicated instances for integration tests that require MySQL or Redis. See the [testing guide (Chinese)](docs/testing.md).

The sample configuration is for local development. The server can read message bodies. See the [configuration guide (Chinese)](docs/configuration.md) for deployment settings.

SChat is licensed under the [Apache License 2.0](LICENSE). Dependencies keep their own licenses. See the [third-party notices](THIRD_PARTY_NOTICES.md).
