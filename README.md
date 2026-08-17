# WebChat 后端

基于 Spring Boot 3.5.3 + Java 21 的即时通讯服务端。

> 完整项目介绍请看根目录 [README.md](../README.md)

---

## 设计原则

**服务器是消息的搬运工，不是保管员。**

- 消息内容：加密后到达服务器 → 立即转发给接收方 → 不写入数据库
- 文件数据：分片到达服务器 → 立即转发给接收方 → 不缓存任何字节
- 服务器持久化的内容仅限：账号信息、公钥、群组结构、Token

---

## 本地运行

```bash
# 需要先有一个 PostgreSQL 实例，然后：
export DATABASE_URL=jdbc:postgresql://localhost:5432/webchat
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=yourpassword
export JWT_SECRET=local-dev-secret-change-me-in-production
./mvnw spring-boot:run -Plocal
# 默认监听 8080 端口，Hibernate 自动建表（ddl-auto: update）
# 本地用 SQLite 开发可激活 local profile，无需 PostgreSQL：
./mvnw spring-boot:run -Plocal -Dspring-boot.run.profiles=local
```

## 生产部署（当前）

| 组件 | 平台 | 说明 |
|---|---|---|
| 后端服务 | **Render** | 免费套餐，无流量时休眠，保活机制防止休眠 |
| 数据库 | **Neon** | Serverless PostgreSQL，连接串通过 `DATABASE_URL` 注入 |
| 保活 | **UptimeRobot** + 后端自检（`SELF_URL`）+ 前端心跳 | 三层保活：UptimeRobot 外部 ping、KeepaliveTask 每 10 分钟自请求、前端 MainLayout 每 4 分钟请求 `/actuator/health` |

## Docker 部署

```bash
docker build -t webchat-backend .
docker run -p 8080:8080 \
  -e JWT_SECRET=your-secret-key-at-least-32-chars \
  -e FRONTEND_URL=https://your-frontend-domain.com \
  -v /data:/data \
  webchat-backend
```

---

## 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `JWT_SECRET` | JWT 签名密钥，生产环境必须通过此变量注入，不少于 32 字符 | 无（启动报错） |
| `DATABASE_URL` | PostgreSQL 连接串，格式：`jdbc:postgresql://host/db?sslmode=require` | 无（必填） |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 | 无（必填） |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | 无（必填） |
| `FRONTEND_URL` | 前端域名，用于 CORS 白名单，如 `https://your-app.pages.dev` | 空（仅允许 localhost） |
| `SELF_URL` | 后端自身公网地址，用于保活心跳防 Render 休眠，如 `https://your-backend.onrender.com` | 空（不发起保活） |
| `PORT` | 服务监听端口 | `8080` |

---

## 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 21 | 运行环境 |
| Spring Boot | 3.5.3 | 服务端框架 |
| Spring Security | 6 | 认证鉴权 |
| JJWT | 0.12 | JWT 生成与校验 |
| Spring WebSocket | — | 实时消息中转 |
| PostgreSQL + Hibernate JPA | — | 持久化（账号/群组/离线消息） |
| Docker | — | 容器化部署 |

---

## 项目结构

```
src/main/java/com/chat/project/chat/
├── ChatApplication.java        # 启动类（排除 DataSourceAutoConfiguration，@EnableScheduling）
├── config/
│   ├── DataSourceConfig.java   # 手动构建 HikariDataSource（绕过 Spring Boot 自动配置）
│   ├── SecurityConfig.java     # Spring Security 配置
│   ├── WebSocketConfig.java    # WebSocket 注册（buffer 256KB）
│   └── KeepaliveTask.java      # 每 10 分钟自检保活防 Render 休眠；每小时清理过期离线消息和过期历史群密钥；每天凌晨 2 点清理 30 天未登录死用户；每天凌晨 4 点清理过期 Refresh Token
├── controller/
│   ├── AuthController.java     # 注册/登录/刷新/退出/WS Ticket
│   ├── UserController.java     # 用户信息/公钥/在线列表/注销账号
│   └── GroupController.java    # 群组增删/成员管理/群密钥；事务外广播 WS
├── service/
│   ├── AuthService.java        # JWT 发放与校验逻辑
│   ├── UserService.java        # 用户业务
│   └── GroupService.java       # 群组业务；leaveGroup 返回 LeaveEvent 解耦事务与广播
├── websocket/
│   ├── ChatWebSocketHandler.java  # 核心：消息转发、文件传输、心跳、已读回执
│   └── WsMessage.java             # WebSocket 消息结构
├── entity/                     # JPA 实体（User/Group/GroupMember/GroupKey/...）
├── repository/                 # Spring Data JPA 接口
├── security/                   # JWT Filter、UserPrincipal
└── exception/                  # 统一异常处理（4 类语义异常 + GlobalExceptionHandler）
```

---

## WebSocket 核心逻辑（ChatWebSocketHandler）

### 心跳机制

```
客户端每 25 秒发一次 PING
服务端收到 PING → 记录时间戳 → 回 PONG
服务端每 30 秒扫描一次：超过 90 秒没有 PING → 强制断开 session
session 断开 → 触发 afterConnectionClosed → 广播 USER_OFFLINE
```

目的：解决移动端锁屏后 TCP 连接假存活，在线状态无法更新的问题。

### 文件传输（纯中转设计）

```
发送方 → FILE_TRANSFER_START → 服务器转发给接收方
接收方点接受 → FILE_CHUNK_ACK(chunkIndex=-1) → 服务器转发给发送方（通知可以开始发片）
发送方 → FILE_CHUNK(i) → 服务器转发给接收方
接收方写盘后 → FILE_CHUNK_ACK(i) → 服务器转发给发送方（驱动发下一片）
……
发送方 → FILE_TRANSFER_END → 服务器转发给接收方 → 回 CHAT_DELIVERY 给发送方
接收方重组文件写盘 → FILE_SAVED → 服务器转发给发送方（告知对方已成功接收）
```

关键约束：
- 服务器不生成任何 ACK，只做转发
- 同一用户对（A↔B）之间同时只允许一个传输，双向互斥（不同对话可并发）
- 分片大小：前端原始数据 128KB/片，经 Base64 + AES-GCM 加密 + 再 Base64 后约 227KB，WebSocket buffer 256KB，余量充足
- 任意一方**主动关闭/刷新页面**（前端发 `PAGE_UNLOAD`）：断线时立即清锁并通知对方（`FILE_TRANSFER_ERROR`）
- 网络临时断线（未收到 `PAGE_UNLOAD`）：保留传输锁，等待重连续传，宽限期 5 分钟
- 任意一方可点击进度条「终止」按钮主动取消，双方均收到通知

### 消息回执（MESSAGE_RECEIVED / MESSAGE_READ）

接收方 WS 收到 `NEW_MESSAGE` 后立即发 `MESSAGE_RECEIVED` 回执，服务端转发给发送方，发送方消息状态升级为 `received`（✓✓灰，已送达）。

接收方打开聊天页时，对所有状态为 `sent`/`received` 的消息发送 `MESSAGE_READ` 回执，服务端验证身份后转发给发送方，发送方消息状态升级为 `read`（✓✓蓝，已读）。

- 私聊：验证发送方是已注册用户
- 群聊：验证读者是群成员，防止非成员伪造回执

### 群退出/解散的事务安全

`GroupService.leaveGroup` 是 `@Transactional` 方法，但 WS 广播是副作用，不能在事务内执行（DB 回滚不会撤销已发出的 WS 消息）。

设计：`leaveGroup` 不直接发 WS，而是返回 `sealed interface LeaveEvent`（`Dissolved` 或 `MemberLeft`），`GroupController.leave` 在事务提交后用 `switch` 模式匹配执行广播：

```java
LeaveEvent event = groupService.leaveGroup(groupId, userId); // 事务在此提交
switch (event) {
    case LeaveEvent.Dissolved d  -> // 广播 GROUP_DISSOLVED
    case LeaveEvent.MemberLeft m -> // 广播 GROUP_KEY_ROTATE
}
```

### 异常体系

| 异常类 | HTTP 状态码 | 使用场景 |
|---|---|---|
| `AuthException` | 401 | 密码错误、Token 失效 |
| `ForbiddenException` | 403 | 越权操作（非群主邀请等） |
| `NotFoundException` | 404 | 用户/群组不存在 |
| `BusinessException` | 400 | 业务参数错误（用户名重复等） |

`GlobalExceptionHandler` 还捕获 `DataIntegrityViolationException`（并发注册竞态的 DB 唯一约束冲突）→ 返回 400，避免 500。

### 群密钥轮换

成员退出群组时，后端通知所有在线成员触发 `GROUP_KEY_ROTATE`，群主负责重新生成群密钥并为每个剩余成员分发加密后的新密钥。

---

## REST API

**认证**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录，返回 Access + Refresh Token |
| POST | `/api/auth/refresh` | 用 Refresh Token 换新 Access Token |
| POST | `/api/auth/logout` | 吊销 Refresh Token |
| POST | `/api/auth/ws-ticket` | 签发一次性 WS Ticket（30s 有效，避免 token 出现在 URL） |

**用户**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users/me` | 当前用户信息 |
| PATCH | `/api/users/me` | 更新昵称/头像 |
| DELETE | `/api/users/me` | 注销账号（永久删除账号及所有数据） |
| GET | `/api/users/online` | 在线用户列表（排除自己） |
| GET | `/api/users/{id}` | 按 ID 查询用户 |
| GET | `/api/users/search?keyword=` | 搜索用户 |
| GET | `/api/users/by-username/{username}` | 按用户名查询 |
| PUT | `/api/users/me/public-key` | 上传/更新自己的 ECDH 公钥 |

**群组**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/groups` | 我加入的群组列表 |
| POST | `/api/groups` | 创建群组 |
| GET | `/api/groups/{id}` | 群组详情（含成员列表） |
| POST | `/api/groups/{id}/members/{userId}` | 邀请成员 |
| DELETE | `/api/groups/{id}/members/me` | 退出群组（群主退出=解散） |
| PUT | `/api/groups/{id}/keys` | 上传某成员的加密群密钥 |
| GET | `/api/groups/{id}/keys/me` | 获取自己的加密群密钥 |

**健康检查**

```
GET /actuator/health
```

---

## 数据库表结构（简要）

| 表 | 存储内容 |
|---|---|
| `users` | 用户名、昵称、头像、BCrypt 密码、ECDH 公钥、最后登录时间 |
| `device_tokens` | Refresh Token 哈希值、过期时间（用于 Token 轮换与吊销） |
| `groups` | 群组名称、群主 |
| `group_members` | 群组成员关系 |
| `group_keys` | 每个成员的加密群密钥（密文，服务器无法解密）；含 `key_version` 字段（每次轮换 +1） |
| `group_key_history` | 历史版本群密钥（轮换前旧版本，TTL 96h），供离线消息解密用 |
| `offline_messages` | 离线文字消息临时暂存（加密密文），含 `group_id`/`group_key_version` 字段；上线后立即投递并删除，最长保留 3 天 |

> 不存在消息表。在线消息只在内存中转发，不落库；离线消息暂存后必然删除。
> 群消息离线暂存条件：发消息时消息类型为文字，且群内有离线成员；在线成员实时收到，离线成员同时存一份离线消息，上线后自动投递。
