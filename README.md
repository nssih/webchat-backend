# WebChat 后端

基于 Spring Boot 4 + Java 25 的即时通讯服务端。

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
./mvnw spring-boot:run
# 默认监听 8080 端口
# SQLite 数据库自动创建在 ./webchat.db（开发环境）
```

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
| `JWT_SECRET` | JWT 签名密钥，生产环境必须修改，不少于 32 字符 | 内置测试值 |
| `FRONTEND_URL` | 前端域名，用于 CORS 白名单 | 空（允许所有） |
| `DB_PATH` | SQLite 数据库文件路径 | `/data/webchat.db` |
| `PORT` | 服务监听端口 | `8080` |

---

## 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 25 (--enable-preview) | 运行环境 |
| Spring Boot | 4.1.0 | 服务端框架 |
| Spring Security | 6 | 认证鉴权 |
| JJWT | 0.12 | JWT 生成与校验 |
| Spring WebSocket | — | 实时消息中转 |
| SQLite + Hibernate JPA | — | 轻量持久化 |
| Docker | — | 容器化部署 |

---

## 项目结构

```
src/main/java/com/chat/project/chat/
├── ChatApplication.java        # 启动类（@EnableScheduling）
├── config/
│   ├── SecurityConfig.java     # Spring Security 配置
│   ├── WebSocketConfig.java    # WebSocket 注册（buffer 512KB）
│   └── KeepaliveTask.java      # 每 10 分钟自检保活，防 Render 休眠
├── controller/
│   ├── AuthController.java     # 注册/登录/刷新/退出
│   ├── UserController.java     # 用户信息/公钥/在线列表
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
发送方 → FILE_TRANSFER_END → 服务器转发给接收方，回 CHAT_DELIVERY 给发送方
```

关键约束：
- 服务器不生成任何 ACK，只做转发
- 同时最多 11 个并发传输（Semaphore 控制）
- 分片大小 360KB，WebSocket buffer 512KB，Base64 加密后约 493KB
- 接收方/发送方任一方断线，立即通知对方并释放资源

### 已读回执（MESSAGE_READ）

接收方打开聊天页时，对所有状态为 `sent` 的消息发送 `MESSAGE_READ` 回执，服务端验证身份后转发给消息发送方，发送方消息状态升级为 `delivered`（✓✓）。

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

**用户**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users/me` | 当前用户信息 |
| PATCH | `/api/users/me` | 更新昵称/头像 |
| GET | `/api/users/online` | 在线用户列表（排除自己） |
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
| `users` | 用户名、昵称、头像、BCrypt密码、ECDH公钥 |
| `refresh_tokens` | Refresh Token 哈希值、设备信息、过期时间 |
| `groups` | 群组名称、群主 |
| `group_members` | 群组成员关系 |
| `group_keys` | 每个成员的加密群密钥（密文，服务器无法解密） |

> 不存在消息表。消息只在内存中转发，不落库。
