<p align="center">
    <a href="https://linux.do" alt="LINUX DO">
        <img
            src="https://img.shields.io/badge/LINUX-DO-FFB003.svg?logo=data:image/svg%2bxml;base64,DQo8c3ZnIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiPjxwYXRoIGQ9Ik00Ni44Mi0uMDU1aDYuMjVxMjMuOTY5IDIuMDYyIDM4IDIxLjQyNmM1LjI1OCA3LjY3NiA4LjIxNSAxNi4xNTYgOC44NzUgMjUuNDV2Ni4yNXEtMi4wNjQgMjMuOTY4LTIxLjQzIDM4LTExLjUxMiA3Ljg4NS0yNS40NDUgOC44NzRoLTYuMjVxLTIzLjk3LTIuMDY0LTM4LjAwNC0yMS40M1EuOTcxIDY3LjA1Ni0uMDU0IDUzLjE4di02LjQ3M0MxLjM2MiAzMC43ODEgOC41MDMgMTguMTQ4IDIxLjM3IDguODE3IDI5LjA0NyAzLjU2MiAzNy41MjcuNjA0IDQ2LjgyMS0uMDU2IiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZWNlY2VjO2ZpbGwtb3BhY2l0eToxIi8+PHBhdGggZD0iTTQ3LjI2NiAyLjk1N3EyMi41My0uNjUgMzcuNzc3IDE1LjczOGE0OS43IDQ5LjcgMCAwIDEgNi44NjcgMTAuMTU3cS00MS45NjQuMjIyLTgzLjkzIDAgOS43NS0xOC42MTYgMzAuMDI0LTI0LjM4N2E2MSA2MSAwIDAgMSA5LjI2Mi0xLjUwOCIgc3R5bGU9InN0cm9rZTpub25lO2ZpbGwtcnVsZTpldmVub2RkO2ZpbGw6IzE5MTkxOTtmaWxsLW9wYWNpdHk6MSIvPjxwYXRoIGQ9Ik03Ljk4IDcwLjkyNmMyNy45NzctLjAzNSA1NS45NTQgMCA4My45My4xMTNRODMuNDI2IDg3LjQ3MyA2Ni4xMyA5NC4wODZxLTE4LjgxIDYuNTQ0LTM2LjgzMi0xLjg5OC0xNC4yMDMtNy4wOS0yMS4zMTctMjEuMjYyIiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZjlhZjAwO2ZpbGwtb3BhY2l0eToxIi8+PC9zdmc+" /></a>
</p>

# Arena 联盟指挥中心（arena-alliance）

为 [Arena Hero](https://doc.arenahero.io/zh-Hans/) 打造的**联盟管理平台**：成员把游戏 apikey 托管给联盟，平台自动完成——

- 🗺 **全联盟实时地图**：聚合所有成员 key 的视野（自己的单位永远全量可见），拼出一张跨越战争迷雾的联盟大地图，登录后主路径 `/` 可见，SSE 实时刷新
- 🛡 **内部攻击实时拦截**：游戏机制中同一玩家后提交的 AGENT 计划会覆盖先前计划，且计划回执会广播给该玩家的所有连接。平台监听每个成员的作战计划，一旦发现攻击联盟成员的动作（SWEEP/SHOOT 落在成员对象上），立即**用攻击者本人的 key 提交"净化计划"**覆盖——只把攻击动作改为 WAIT，采集/移动等正常动作原样保留
- ⚖ **伤亡裁决与自动执法**：网页手操（MANUAL）优先级高于 AGENT 无法拦截。若内部攻击仍造成伤亡，平台通过攻守双方事件交叉归因（`destroyed_by` 用户名 / `SHOT_HIT` target / `SWEEP` 格位），按规则**自动踢出攻击者**（默认：死单位或伤 Core 即踢；仅掉护盾记警告，累计可踢）
- 🔥 **外部攻击告警**：外部玩家攻击成员时（无对方 key、无法拦截），地图打脉冲标记 + 事件流告警
- 🤖 **联盟托管（指挥官）**：成员可把账号交给"联盟指挥官"托管——**从全联盟视角统一指挥**（态势评估 → 联盟目标 → 跨账号任务分派 → 单账号执行）：资源分片不互抢、自动生产治疗；威胁分级响应（远处静止不动员／移动警戒／进圈预撤／接战反击）、Worker 遇敌回撤、Core 预撤迁移；**成员遇袭时邻近托管账号自动驰援**（含保护未托管的成员）；**侦察扇区分工**（按联盟共享记忆统计未探明 chunk，跨账号互不重叠地扩图）；**清野战役**（重复观测确认长期静止的目标后，跨账号编组集火，48/56 格出击与释放约束、留守底线）。可选角色模式：⚔ 我为联盟扫清障碍 / 🛡 别浪等我发育 / 🔭 我愿为联盟探明一切 / ⚖ 稳健运营。检测到成员本地 agent 提交计划会**自动冲突暂停**；托管计划提交前强制过盟友安全闸
- 🎮 **人工接管**：托管运行中可在地图底部开启手动操控（多账号时只能操作自己已托管的账号）。点击自己的核心/单位后，操作面板会**贴近该对象**显示；移动以青色目标格/路径反馈，扫击与射击以红色范围、弹道和命中效果反馈，Core 生产使用独立三列按钮；顶部同时显示约 15 秒 Tick 指令窗口倒计时。核心迁移按官方 4-Tick 语义处理（迁移期间只能继续/取消/自毁，且无法生产、治疗、接收上缴）。**手动操作的单位以用户指令为准，未手动的单位继续由指挥官接管**；任何违反联盟规则的指令会被直接拒绝并提示原因
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
每个 Key 可单独关闭向其他成员显示自己的 Core（本人仍可见），或把盟友名册设为仅返回对象 ID（不返回坐标和单位类型）。

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
| 联盟托管总闸（关闭后所有托管账号暂停指挥，成员开关保留） | 开 |

### 成员自己的开关（「我的 Key」→ 编辑）

| 设置 | 默认 | 说明 |
|---|---|---|
| 向其他成员显示我的核心 | 开 | 关闭后地图上仅自己可见自己的 Core |
| 盟友名册仅返回对象 ID | 关 | 开启后不对外发送坐标与单位类型 |
| 联盟托管 | 关 | 开启后由指挥官统一指挥该账号 |
| 托管模式 | ⚖ 稳健运营 | ⚔ 扫清障碍 / 🛡 别浪发育 / 🔭 探明一切 / ⚖ 稳健运营 |

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

## 安全说明

| 风险 | 防护 |
|---|---|
| 伪造请求（不知道服务端密钥） | 会话与名册令牌都是 HMAC 签名，改一个字节即失效 |
| 跨站请求伪造（CSRF） | Cookie `SameSite=Lax` + 状态变更一律用 POST/PUT |
| XSS 窃取会话 | Cookie `HttpOnly`，JS 读不到 |
| **抓包窃取会话** | HTTPS 下自动加 `Secure`（识别 `X-Forwarded-Proto`），禁止明文回传；**生产务必启用 HTTPS** |
| **重放抓到的人工指令** | 指令带 `expectedTick`，世界推进后旧包直接 409 失效 |
| 篡改前端绕过校验 | 服务端基于最新 state 重算可用动作与目标格，不信任前端提交的 enabled/targets |
| 越权操作他人账号 | 每个敏感接口都校验对象归属（key/账号/单位必须属于本人） |
| 攻击盟友 | 人工指令与托管计划都过同一套 `ThreatMonitor` 红线复核 |
| 会话被盗后高频滥用 | 人工指令限流（15 秒 30 次/用户） |
| 名册令牌泄露 | 令牌带版本号，「重置令牌」后旧令牌立即全部失效 |

> ⚠️ 公网部署请务必用 HTTPS（反代终止 TLS 时透传 `X-Forwarded-Proto`），否则会话 Cookie 可被中间人抓取复用。

## 架构

```
com.arena.alliance
├── config/     AllianceProperties · NettyConfig(共享 EventLoop/连接池) · WebConfig
├── common/     CryptoService(AES-GCM/HMAC) · ApiResponse · 全局异常
├── game/       纯协议层：DTO(UnitType 含视野/血量) · GameJson
│               GameWsClient(重连退避) · GameCommandClient
├── engine/     WorldAggregator(全联盟视野聚合·supercover 视线) · ThreatMonitor
│               PlanSanitizer · MemberSession · CasualtyJudge(伤亡法庭)
│               Enforcer(执法) · AllianceEngine(会话编排)
├── hosting/    联盟托管（指挥官）
│   ├── HostingService        开关/冲突暂停/指纹池/受托提交/人工指令覆盖
│   ├── CommanderScheduler    Tick 对齐屏障 + 单线程指挥循环
│   ├── AllianceBlackboard    黑板：账号快照 + 租约/侦察/观测/打击台账
│   ├── ManualControl·ManualTargeting  人工接管：协议校验 · 目标格与红线标记
│   ├── HostingController     托管与人工接管 API
│   └── plan/                 纯函数指挥内核（可录快照回放）
│       ├── CommanderPlanner  管线编排：威胁→驰援→清野→经济→侦察→编译→安全闸
│       ├── ThreatAssessor    四级威胁状态机   · DefensePlanner  守位/反击/预撤
│       ├── ScoutDoctrine     探索扇区分工     · ClearDoctrine   静止目标集火
│       ├── ProductionPlanner 阵容阶段与动态价格 · Pathfinder     共享记忆 BFS
│       └── AllySafetyGate    盟友红线终检（复用 ThreatMonitor）
├── auth/       LinuxDo OAuth · 账号密码 · HMAC 会话 · 拦截器（首个用户→管理员）
├── user/ apikey/ rules/ incident/   领域层（JPA：SQLite / PostgreSQL）
├── map/        快照组装(按查看者隐私过滤) · SSE 推送 · 盟友名册接口
└── admin/      规则/成员/审计管理 API

前端：Vue 3（CDN 本地化，无构建步骤）+ Canvas 战场渲染 + 霓虹玻璃拟态设计系统
     页面 / (联盟地图·人工接管) · /login.html · /keys.html(编辑抽屉) · /rules.html · /admin.html
```

**扩展点**：新玩法 = 加一个 Doctrine 并接入 `CommanderPlanner` 管线；新微操 = 加一个规划器方法；
新托管风格 = `PilotMode` 加一个枚举值 + `HostingConfig.of` 一行预设。黑板、调度、提交链路都不用改。

## 测试

```bash
mvn test     # 105 个用例：纯函数单测 + FakeArenaServer 端到端（H2 内存库，无需 PG/外网）
```

覆盖范围：

| 领域 | 关键用例 |
|---|---|
| 视野聚合 | 敌人离开视野即消失、资源重入视野发现消失才删除、障碍遮挡不误删 |
| 保护体系 | 攻击识别（扫击/射击几何）、净化计划只改攻击动作、伤亡归因、执法规则 |
| 指挥内核 | 资源跨账号不互抢、租约防抖、生产阶段与动态价格档、治疗预算 |
| 防御条令 | 威胁四级矩阵（静止不动员/移动警戒/逼近预撤/接战）、守位、Core 预撤择向、跨账号驰援 |
| 侦察条令 | chunk 覆盖度、扇区互斥、编制配额、负坐标分块正确性 |
| 清野战役 | 静止确认与移动清零、集火上限、48/56 距离约束、留守底线、模式限制 |
| 人工接管 | 协议动作校验、Core 4-Tick 迁移语义、目标格计算与障碍遮挡、盟友红线 |
| 端到端 | 内部攻击→拦截→伤亡→踢出；双托管账号协同采集 + 冲突暂停隔离 |

## 常见问题

**LinuxDo 登录报 `ConnectTimeoutException: connect.linux.do`**：本机网络无法直连 connect.linux.do（常见于 DNS 污染，比如解析到 199.16.x.x）。设置 `OAUTH_PROXY=http://127.0.0.1:7890`（你的代理端口）后重启即可；Docker 内注意代理地址要用宿主机可达地址（如 `http://host.docker.internal:7890`）。

**游戏连不上 / key 一直"识别中"**：确认 key 有效且 `ARENA_API_BASE` 可达；必要时配 `GAME_PROXY`。key 无效会自动标记"凭证失效"并停止重连。

## 已知限制

- 手操（MANUAL）攻击无法拦截，只能事后裁决踢人
- 外部攻击只能告警，不能代操防御（避免与成员自己的 agent 打架）
- 攻击者若在平台反制后**换新 key** 提交攻击（旧 key 已交平台），同槽位仍会被覆盖；但若其撤销托管的 key，平台会失去拦截与视野能力并告警
- 同一游戏账号的多个 key 只保留一条连接（多凭证共享同一 AGENT 槽位，重复接入无意义）
