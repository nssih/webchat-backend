# WebChat Backend

> 轻量、隐私优先的即时通讯服务端。聊天记录 100% 存储在用户设备，服务器不留任何消息数据。

## 核心特性

**隐私优先**
- 服务器零存储聊天记录，消息仅在服务端转发，不写入任何数据库
- 聊天记录全部保存在客户端 IndexedDB，断网也可查看历史消息
- 仅存储账号基本信息（用户名、昵称、好友关系、群组信息）

**实时通信**
- 基于原生 WebSocket，支持单聊和群聊实时消息推送
- 支持文字、图片、文件多种消息类型
- 消息送达回执（发送中 → 已发送 → 已送达）
- 好友申请实时通知

**多设备支持**
- 同一账号可在手机、电脑等多个设备同时登录
- 每台设备独立 Refresh Token，退出单设备不影响其他设备
- 多设备同步接收消息

**安全认证**
- JWT 双 Token 机制（Access Token 24h / Refresh Token 30天）
- Access Token 过期自动静默刷新，用户无感知
- BCrypt 密码加密存储

**PWA 支持**（配合前端）
- 支持添加到手机桌面，体验接近原生 App
- Service Worker 离线缓存静态资源

---

## 技术栈

- Java 25 + Spring Boot 4.1.0
- Spring Security + JWT 鉴权
- WebSocket 实时通信
- SQLite + Hibernate JPA
- Docker 容器化部署

## 本地运行

```bash
./mvnw spring-boot:run
```

默认端口 `8080`，数据库文件自动创建在 `/data/webchat.db`。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `JWT_SECRET` | JWT 签名密钥，生产环境必须修改，不少于 32 字符 | 内置默认值 |
| `FRONTEND_URL` | 前端域名，用于 CORS 白名单 | 空 |
| `DB_PATH` | SQLite 数据库文件路径 | `/data/webchat.db` |
| `UPLOAD_PATH` | 上传文件存储路径 | `/data/uploads` |
| `PORT` | 服务监听端口 | `8080` |

## Docker 部署

```bash
docker build -t webchat-backend .
docker run -p 8080:8080 \
  -e JWT_SECRET=your-secret-key \
  -e FRONTEND_URL=https://your-frontend.pages.dev \
  -v /data:/data \
  webchat-backend
```

## 健康检查

```
GET /actuator/health
```

## API 文档

**认证** `POST /api/auth/register` · `POST /api/auth/login` · `POST /api/auth/refresh` · `POST /api/auth/logout`

**用户** `GET /api/users/search` · `PUT /api/users/profile`

**好友** `GET /api/friends` · `POST /api/friends/request/{id}` · `POST /api/friends/request/{id}/accept` · `DELETE /api/friends/{id}`

**群组** `GET /api/groups` · `POST /api/groups` · `POST /api/groups/{id}/members` · `DELETE /api/groups/{id}/members`

**文件** `POST /api/files/image` · `POST /api/files/file`

**WebSocket** `ws://host/ws/chat?token=<access_token>`