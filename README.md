# Arena 联盟指挥中心（arena-alliance）

为 [Arena Hero](https://doc.arenahero.io/zh-Hans/) 打造的**联盟管理平台**：成员把游戏 apikey 托管给联盟，平台自动完成——

- 🗺 **全联盟实时地图**：聚合所有成员 key 的视野（自己的单位永远全量可见），拼出一张跨越战争迷雾的联盟大地图，登录后主路径 `/` 可见，SSE 实时刷新
- 🛡 **内部攻击实时拦截**：游戏机制中同一玩家后提交的 AGENT 计划会覆盖先前计划，且计划回执会广播给该玩家的所有连接。平台监听每个成员的作战计划，一旦发现攻击联盟成员的动作（SWEEP/SHOOT 落在成员对象上），立即**用攻击者本人的 key 提交"净化计划"**覆盖——只把攻击动作改为 WAIT，采集/移动等正常动作原样保留
- ⚖ **伤亡裁决与自动执法**：网页手操（MANUAL）优先级高于 AGENT 无法拦截。若内部攻击仍造成伤亡，平台通过攻守双方事件交叉归因（`destroyed_by` 用户名 / `SHOT_HIT` target / `SWEEP` 格位），按规则**自动踢出攻击者**（默认：死单位或伤 Core 即踢；仅掉护盾记警告，累计可踢）
- 🔥 **外部攻击告警**：外部玩家攻击成员时（无对方 key、无法拦截），地图打脉冲标记 + 事件流告警
- 🔑 **LinuxDo 登录 + 账号密码**：`connect.linux.do` OAuth2（实现参考 new-api），也支持本地账号密码注册/登录（管理员开关控制，默认关）。**第一个登录/注册的用户自动成为管理员**，可在后台配置全部明细规则；一个账号可上传多个 apikey（多个游戏账号）

## 快速开始

### Docker Compose（推荐）

```bash
# 1. 配置 LinuxDo OAuth（见下文"申请 OAuth 应用"），写入 .env 或直接 export
export LINUXDO_CLIENT_ID=xxx
export LINUXDO_CLIENT_SECRET=xxx
export PUBLIC_BASE_URL=https://你的域名        # 本机测试可留空

# 2. 启动（内置 PostgreSQL 16）
docker compose up -d --build

# 3. 打开 http://localhost:8080 ，用 LinuxDo 登录（第一个登录者即管理员）
```

### 手动运行（本地零依赖，默认 SQLite）

需要 JDK 21+ 和 Maven，**无需安装数据库**（默认使用 SQLite，库文件在 `data/alliance.db`；生产可通过 `DB_URL` 切换 PostgreSQL）：

```bash
mvn package -DskipTests
java -jar target/arena-alliance.jar
```

本地想先看界面、不配 OAuth：设 `DEV_LOGIN_ENABLED=true` 后，登录页会出现"开发登录"输入框（生产环境务必保持关闭）。

## 申请 LinuxDo OAuth 应用

1. 打开 <https://connect.linux.do> → 创建新应用
2. 回调地址填：`http(s)://你的部署地址/api/auth/linuxdo/callback`
3. 把 Client ID / Client Secret 配到环境变量 `LINUXDO_CLIENT_ID` / `LINUXDO_CLIENT_SECRET`

> ⚠️ 建议通过环境变量注入，不要把真实 client_secret 写进 `application.yml` 提交到公开仓库。

## 成员使用流程

1. LinuxDo 登录 → 「我的 Key」页粘贴 Arena Hero API Key（AES-GCM 加密落库，绝不回显）
2. 平台自动接入游戏 WS，识别该 key 的游戏用户名，成员出现在联盟地图上
3. 成员自己的 agent（如 arena-hero-agent）**照常运行**，平台只在检测到"攻击联盟成员"时才覆盖计划
4. 「联盟规则」页有完整公约与**盟友判定接口教程**：agent 用个人接入令牌调 `GET /api/alliance/roster` 拉取盟友名册（游戏用户名 + 存活对象 ID + 位置），出手前排除盟友目标
5. 若被踢出：本人所有 key 停用、地图不可见；管理员可在后台恢复（key 需成员自行重新启用）

未上传任何游戏 Key 的账号不能访问联盟地图、地图数据或盟友名册接口；删除最后一个 Key 后权限会立即收回。
每个 Key 可单独关闭自己的 Core 地图显示，或把盟友名册设为仅返回对象 ID（不返回坐标和单位类型）。

## 联盟规则（管理后台可改，即时生效）

| 规则 | 默认 |
|---|---|
| 内部攻击实时拦截 | 开 |
| 每成员每 Tick 最大覆盖次数（服务器上限 64 次/Tick/槽位） | 8 |
| 致对方单位死亡 → 踢出 | 开 |
| 致对方 Core 掉血 → 踢出 | 开 |
| 致对方 Core 被摧毁 → 踢出 | 开 |
| 仅掉护盾/单位掉血未死 → 记警告 | 开 |
| 警告累计 N 次自动踢出 | 3 |
| 外部攻击告警 | 开 |
| LinuxDo 最低信任等级 | 0 |
| 开放注册 | 开 |
| 允许账号密码注册（首个用户注册不受限） | 关 |

## 配置项

| 环境变量 | 说明 | 默认 |
|---|---|---|
| `PORT` | 服务端口 | 8080 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 数据库连接（本地默认 SQLite；compose 内为 PostgreSQL 16） | `jdbc:sqlite:./data/alliance.db` |
| `DB_POOL_SIZE` | 连接池大小（SQLite 保持 1，PostgreSQL 建议 10） | 1 |
| `LINUXDO_CLIENT_ID` / `LINUXDO_CLIENT_SECRET` | LinuxDo OAuth 应用凭证 | 空 |
| `PUBLIC_BASE_URL` | 对外地址（拼 OAuth 回调；留空按请求 Host 推断） | 空 |
| `ALLIANCE_SECRET` | 根密钥（apikey 加密 + 会话签名派生）；留空首次启动自动生成到 `data/secret.key` | 自动生成 |
| `ARENA_API_BASE` | 游戏 API 地址（测试服/自建服可换） | `https://api.arenahero.io` |
| `OAUTH_PROXY` | 访问 connect.linux.do 的代理（`http://127.0.0.1:7890` 或 `socks5://…`）；**直连超时/DNS 污染时必配** | 空 |
| `GAME_PROXY` | 访问游戏 API 的代理（可选） | 空 |
| `DEV_LOGIN_ENABLED` | 本地开发登录开关 | false |
| `SESSION_TTL_HOURS` | 登录会话有效期 | 168 |

## 工作原理（对应游戏机制）

```
每个成员 key ──Netty WS──▶ wss://api.arenahero.io/api/v1/game/ws
    │  tick     记录当前回合，重置覆盖计数
    │  state    ├─ WorldAggregator 聚合全联盟视野（障碍永久记忆并持久化）
    │           └─ CasualtyJudge 收集决议事件作为"证据"
    │  received ├─ ThreatMonitor 审查该玩家当前存储的计划
    │           ├─ 有攻击成员的动作 → PlanSanitizer 生成净化计划
    │           └─ POST /api/v1/game/commands（后写覆盖先写，仅 AGENT 槽位）
    └─ 每 Tick 结算后：伤亡法庭跨成员交叉归因 → Enforcer 按规则警告/踢出
```

关键边界（来自官方协议，详见 `参考资料/arena-hero-doc`）：

- **MANUAL（网页手操）> AGENT**：手操攻击拦不住——这正是"阻止不了导致伤亡→踢出"规则存在的原因
- 覆盖与攻击者 agent 的重新提交是**同槽位竞速**，每 Tick 每槽位最多 64 次提交、4 并发，平台默认最多覆盖 8 次/Tick 防止打满配额
- 视野拼合以各成员自身对象为准（永远全量），敌方对象仅在可见时出现并按目击时效淡出

## 架构

```
com.arena.alliance
├── config/     AllianceProperties · NettyConfig(共享 EventLoop/连接池) · WebConfig
├── common/     CryptoService(AES-GCM/HMAC) · ApiResponse · 全局异常
├── game/       纯协议层：DTO · GameJson · GameWsClient(重连退避) · GameCommandClient
├── engine/     WorldAggregator · ThreatMonitor · PlanSanitizer · MemberSession
│               CasualtyJudge(伤亡法庭) · Enforcer(执法) · AllianceEngine(编排)
├── auth/       LinuxDo OAuth · HMAC 会话 · 拦截器（首个用户→管理员）
├── user/ apikey/ rules/ incident/   领域层（JPA + PostgreSQL）
├── map/        快照组装 · SSE 推送
└── admin/      规则/成员/审计管理 API
前端：原生 JS + Canvas（深色战场风格，无构建步骤），页面 / · /login.html · /keys.html · /admin.html
```

## 测试

```bash
mvn test          # 引擎纯函数单测 + FakeArenaServer 端到端（模拟内部攻击→拦截→伤亡→踢出全流程，H2 内存库，无需 PG）
```

## 常见问题

**LinuxDo 登录报 `ConnectTimeoutException: connect.linux.do`**：本机网络无法直连 connect.linux.do（常见于 DNS 污染，比如解析到 199.16.x.x）。设置 `OAUTH_PROXY=http://127.0.0.1:7890`（你的代理端口）后重启即可；Docker 内注意代理地址要用宿主机可达地址（如 `http://host.docker.internal:7890`）。

**游戏连不上 / key 一直"识别中"**：确认 key 有效且 `ARENA_API_BASE` 可达；必要时配 `GAME_PROXY`。key 无效会自动标记"凭证失效"并停止重连。

## 已知限制

- 手操（MANUAL）攻击无法拦截，只能事后裁决踢人
- 外部攻击只能告警，不能代操防御（避免与成员自己的 agent 打架）
- 攻击者若在平台反制后**换新 key** 提交攻击（旧 key 已交平台），同槽位仍会被覆盖；但若其撤销托管的 key，平台会失去拦截与视野能力并告警
- 同一游戏账号的多个 key 只保留一条连接（多凭证共享同一 AGENT 槽位，重复接入无意义）
