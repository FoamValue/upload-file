# upload-file 组件 rc.8 使用反馈（供组件升级优化参考）

> 本文件由组件使用者（PathFinder 文件管理系统）整理，用于向 `cn.chenxinjie:upload-file`
> 反馈 **1.0.0-rc.8** 的真实使用情况、利好兑现、残留风险与增强诉求，供组件侧 GA 及后续
> `1.0.x / 1.1` 评估参考。本工程侧**不修改组件代码**；仅按现状如实反馈。
>
> 关联历史反馈：
> - [upload-file-usage-feedback.md](upload-file-usage-feedback.md)（rc.3 基线，core 手工装配）
> - [upload-file-rc6-migration-feedback.md](upload-file-rc6-migration-feedback.md)（rc.6 迁移评审）
> - [upload-file-rc7-usage-feedback.md](upload-file-rc7-usage-feedback.md)（rc.7 存储正确性与扩展点收口）

## 1. 评审对象与接入环境

| 项 | 内容 |
|---|---|
| 集成项目 | path-finder（文件管理系统，单组织私有部署） |
| 集成时点 | 组件 `1.0.0-rc.8`；path-finder 基线 commit `fec319e` + 工作区 rc.6/rc.7/rc.8 迁移改动 |
| 运行环境 | JDK 26 · Spring Boot 4.1.1 · Spring Security · Redis 9（部署）/ 本地 Redis 7 · MySQL 8 |
| 实际引用产物 | `upload-file-spring-boot-starter-jakarta` + `upload-file-store-redis`，版本经 **`upload-file-bom:1.0.0-rc.8`** 统一管理 |
| 迁移依据 | path-finder `docs/design/ADR-001-upload-file-starter-jakarta.md`、`UPGRADE-upload-file-starter-jakarta.md`、`PLAN §16`（PF-1001~PF-1005） |
| 端点形态 | `/upload` 由组件 `UploadServlet` 承载；`/download` 保持默认关闭（业务下载走 `/api/file/download/{token}`） |
| 业务形态 | 分片（5MB）→ MD5 校验 → 断点续传 → 异步合并轮询 → confirm 按 `finalPath` 迁库 → `cancelUpload` 回收 |
| 测试结果 | `mvn test` 全绿：**164 例 / 0 失败**（含真实 MySQL/Redis 集成用例）；`/upload` 契约与成功体不变，前端零改动 |

> ⚠️ **产物与组件仓 HEAD 的差异（重要）**：本工程消费的 rc.8 来自本地 `.m2` 安装件
> （`upload-file-store-redis-1.0.0-rc.8.jar` 构建于 2026-09-15），而组件仓 HEAD `8eb31ca`
> （2026-09-16「修复 rc.8 验证发现的发布工程与降噪缺陷」）已包含后续修正，例如：
> `RedisIdentifierLockProvider` 续租 watchdog 由 1 线程改为 **4 线程共享池**
> （`WATCHDOG_THREADS=4`，`RedisIdentifierLockProvider.java:79`）、access-log 首分片放行改为
> **有界 LRU**（`admitAccessLog` / `ACCESS_LOG_TRACKED_IDENTIFIERS=10_000`，
> `UploadFileAutoConfiguration.java:480,494`）。本反馈的**源码证据均以组件仓 HEAD 为准**，
> 并建议在 GA 前**重新安装/发布 rc.8 后复核**本工程消费的字节码与 HEAD 一致。

## 2. rc.8 能力使用总览

图例：✔ 已用（生产） ｜ ◐ 部分/变通使用 ｜ ✖ 未使用 ｜ N/A 不适用

| rc.8 能力 | 使用 | 说明与证据 |
|---|---|---|
| `upload-file-bom:1.0.0-rc.8` | ✔ | `server/pom.xml:24,32`；starter-jakarta 与 store-redis 去显式 version |
| `QuotaStore.reconcile(TaskStore)` 新增 `default` | ✔（间接） | `quota.store=task-store` 默认，空实现等价 rc.7；未启用 redis 配额 |
| `RedisQuotaStore` 启动自动对账 | ✖ | `quota.max-bytes=0`（配额关闭），未启用 `quota.store=redis` |
| 孤儿清理回收配额（G17） | ✖（间接） | `cleanup.orphan-enabled=true`；配额关闭时不记账 |
| `RedisIdentifierLockProvider` 续租 | ✖ | `lock.identifier-lock=local`（单实例） |
| `RedisTaskStore` 原子迁移 + 分批 `MGET` | ✔（无感） | `metadata-store=redis`；升级未做任何 Redis 数据操作 |
| `AccessContext`（method/URI/IP/UA） | ✔ | `UploadAccessAuditListener` 覆写 6 参重载落库（`UploadAccessAuditListener.java:41`） |
| `AccessContextHolder` 空上下文 | ✔（无感） | 服务内 confirm 等无 HTTP 上下文时字段为 null，不抛异常 |
| `AccessControlListener` 6 参 `default` 重载 | ✔ | 覆写 6 参，5 参保留桥接（旧实现零改动） |
| `observability.access-log-scope` | ◐ | `access-log=false`（越权走自建监听器落库）；配置显式登记 `task` 备用 |
| starter 自动装配 `TrustedUploadService` | ✔ | 删除自写 `UploadTrustedConfig`，直接注入（`UploadFileAutoConfiguration.java:328`） |
| `trusted-upload-service.enabled` 开关 | ◐ | 默认 `true`，未显式关闭 |
| 统一信封示例（宿主 `UploadErrorRenderer`） | ✖ | 保持 `http.error-body=legacy`；前端仅消费状态码/文本 |
| 二进制兼容门禁 / SBOM | ✔（组件侧） | 本工程作为消费方受益（升级无编译期破坏） |
| `AbstractAccessControl` / `ofDecide` | ✖ | 直接 `implements AccessControl` 覆写 `decide()` |
| 门控读 `getTask(id, token)` / `isChunkUploaded(id,index,token)` | ✖ | 服务端受信读走 `TrustedUploadService`；HTTP 边界由组件 Servlet 内部门控 |

## 3. rc.8 对 PathFinder 的利好（已兑现）

### 3.1 rc.7 反馈 P1/P2 的闭环对照

rc.8 是「GA 前最后一个 rc」，把 rc.7 反馈在**新引入的多实例/配额能力**上的三条正确性缺陷与 rc.6 顺延的审计项逐条收口：

| 维度 | rc.7 的缺口 | rc.8 的解法 | 对 PathFinder 的价值 |
|---|---|---|---|
| 配额对账（P1-1） | `RedisQuotaStore.reconcile` 无生产调用，Redis 数据丢失后配额低估 | starter 在 `QuotaStore` 装配后启动期调用一次 `reconcile(taskStore)`（`UploadFileAutoConfiguration.java:267`） | 一旦启用 `quota.store=redis` 即自动对齐，无需运维手工调用 |
| 配额泄漏（P1-2） | 「已合并未确认 + 任务 key TTL 淘汰」的 identifier 计数永久泄漏 | 孤儿清理删除无记录目录时同步 `release(identifier)`（`StorageCleanupService.java:291,310,336`） | 修复「没有文件却报 507」，配额能力才算可用 |
| 锁续租（P1-3） | `RedisIdentifierLockProvider` 固定 TTL，长合并锁失效 | 持有期 watchdog 续租（默认 `ttl/3`，Lua owner 校验；`RedisIdentifierLockProvider.java:200-232`） | 多实例路径最后一块正确性短板补齐 |
| 索引迁移（P2-1） | `SMEMBERS → DEL → ZADD` 非原子，迁移期并发写丢条目 | Lua 原子迁移（`RENAME` staging + `ZADD` + `DEL`；`RedisTaskStore.java:66-80`） | 升级零运维且不丢任务 |
| `list()` 大 N（P2-2） | 单次 `MGET` 全量，占用 Redis 单线程 | 分批 `MGET`（`MGET_BATCH_SIZE=500`；`RedisTaskStore.java:57,194-223`） | cleanup 枚举更稳 |
| 审计上下文（rc.6 P2-1） | `onDecision` 无 method/IP/UA | `AccessContext` + `AccessContextHolder` + 6 参 `default` 重载；Servlet 填充（`UploadServlet.java:137-145`） | 越权审计字段与登录审计对齐 |
| access-log 降噪（rc.6 P2-2） | 每分片一行 | `access-log-scope` 默认 `task`，首分片经有界 LRU 放行（`UploadFileAutoConfiguration.java:457,480,494`） | 开启 access-log 也不再刷屏 |
| 受信读自动装配（P2-3） | 宿主需自建 `TrustedUploadService` Bean | starter `@ConditionalOnMissingBean` 自动装配（`:328`） | 本工程删除 `UploadTrustedConfig`，少一个样板类 |
| 统一信封示例（P2-4） | 缺「宿主 Bean → 统一信封」示例 | demo 增 `EnterpriseWiringConfig`（`EnterpriseWiringConfig.java:69-72`） | 需要时可复制即用 |

### 3.2 项目侧接入变化（rc.8 直接红利）

- **BOM 单点版本**：`server/pom.xml:24,32` 引入 `upload-file-bom`，两个组件坐标不再各自写 version，消除版本漂移；
- **删除手写装配**：rc.8 starter 自动装配 `TrustedUploadService`，删除 `UploadTrustedConfig`，`FileService` 注入源不变；
- **审计升级**：`UploadAccessAuditListener` 覆写 6 参重载（`UploadAccessAuditListener.java:41`），越权审计行补齐 method/URI/IP/UA，与 MVC 路径口径一致；
- **配置显式化**：`application.yml:92,95` 登记 `observability.access-log-scope=task` 与 `trusted-upload-service.enabled=true`。

### 3.3 项目侧优化（rc.8 集成后，`mvn test` 164 例全绿）

> 这些是**消费侧**的优化，不改变组件契约；记录在此以便组件了解真实落地成本，并评估是否值得在组件侧标准化。

| 编号 | 优化 | 说明 |
|---|---|---|
| A1 | 分片授权缓存 | 组件对**每个分片请求**都调用 `AccessControl.decide()`（`ResumableUploadService.java:243,353`）。本工程新增 `util/TtlCache`（LRU 有界 + 60s TTL），缓存 identifier→creatorId（`UploadOwnerAccessControl.java:33,50,59`），500MB/5MB 上传的归属 DB 查询由 100+ 次降为 1 次 |
| A2 | confirm 事务与 I/O 分离 | `FileService.confirm` 取消方法级事务，文件迁移 + 整文件 MD5 移出事务，DB `save` 提交成功后才 `cancelUpload`（`FileService.java:297,331,333`），杜绝「任务已删、DB 回滚」不一致 |
| B1 | 回收站分页 | 由「全表 `findAll` + 每条两次 `findById`」改为数据库分页 JOIN + 数据权限谓词下推 |
| B2 | 列表用户名批量加载 | `toVo` 由逐行查用户名（每页最多 200 次）改为一次批量查询 |
| B3 | 500 错误脱敏 | `GlobalExceptionHandler` 对外固定文案，细节仅进日志（PLAN PF-903） |

## 4. 迁移后项目侧仍需盯住的风险

1. **每分片一次授权决策的固有成本**：`gate()` 对每个 chunk 调用一次 `decide()`（`ResumableUploadService.java:243`），进度查询亦然（`:353`）。这是**正确的安全设计**（每请求独立鉴权），但把成本转嫁给宿主。PathFinder 已用 A1 短 TTL 缓存缓解；缓存窗口内软删除可能仍放行（上传中文件，风险可接受）。
2. **未门控 API 收口不完整**（详见 §5 P1-1）：仅 `getTask(id)`/`isChunkUploaded(id,index)` 标 `@Deprecated`，其余无门控读/写方法仍在服务上公开可用，`TrustedUploadService` 也未覆盖 `cancelUpload`。
3. **单实例默认**：`lock.identifier-lock=local` + `quota.store=task-store`，多实例能力已在 rc.7/rc.8 就绪但本工程未启用（PRD 明确非目标多实例）。
4. **消费 artifact 与组件 HEAD 不一致**：见 §1 提示；建议 GA 发布前统一，避免「已修复但未消费」。
5. **`/download` 关闭后无兜底下载入口**：维持最小暴露取舍；如需联调兜底需显式开启并自行评估 nginx 透传与鉴权。
6. **access-log 默认降噪的语义边界**：`task` 档对每个任务只记录首个分片放行（有界 LRU，上限 1 万 identifier）。任务量极大且长期运行后，早期 identifier 会被 LRU 淘汰，同一任务若跨很久再次上传会重新记一行——属预期，非缺陷。

## 5. 组件可提升项（按优先级，附证据）

### P0 — 无

rc.8 未发现影响 PathFinder 生产数据安全的新 P0；rc.7 反馈的 P1-1/P1-2/P1-3 已全部闭环。

### P1 — API 收口一致性与鉴权成本

#### P1-1 未门控读/写 API 收口不完整，`TrustedUploadService` 未覆盖 `cancelUpload` ⚠️

- 证据：`ResumableUploadService` 上仅 `getTask(String)`（`:420`）与 `isChunkUploaded(String,int)`（`:380`）标 `@Deprecated`；而**无门控且未弃用**的方法仍在公开 API：
  `getProgress(String)`（`:344`）、`getMergeStatus(String)`（`:586`）、`merge(String)`（`:454`）、`submitMerge(String)`（`:541`）、`cancelUpload(String)`（`:623`）。
  受信读门面 `TrustedUploadService`（`TrustedUploadService.java:27`）只暴露 `getProgress/getTask/isChunkUploaded/getMergeStatus/getTaskStore`，**没有 `cancelUpload`**。
- 影响：PathFinder 的 confirm 回收只能直接调用无门控的 `uploadService.cancelUpload(identifier)`（`FileService.java:373`），与「受信读走门面」的收口理念不一致——写操作仍可被误用在 HTTP 边界。
- 建议：把 `cancelUpload` 纳入 `TrustedUploadService`（对称收口），并对剩余无门控读方法给出统一策略（弃用 + 门面覆盖，或文档明确「写操作仅服务端受信调用」）。

#### P1-2 缺少标准化的「授权决策缓存 / 单任务门控」指引

- 证据：`gate()` 每请求一次 `decide()`（`ResumableUploadService.java:243,353,463,550,595,632`）。宿主若实现 DB 归属查询，成本随分片数线性增长（PathFinder 500MB/5MB = 100+ 次/文件）。
- 影响：这是每个 DB 型 `AccessControl` 实现方都会踩的坑；组件文档目前未提示「decide 按请求/分片调用，建议宿主自缓存」。
- 建议：① 文档明确 `decide()` 的调用频次与幂等预期；② 可选提供 core 的 `CachingAccessControl` 装饰器（带 TTL 与有界 LRU，`@ConditionalOnMissingBean` 或显式装配），把 PathFinder 的 A1 做法标准化；③ 或提供 `AccessControl` 的「任务级一次性门控」扩展点。

### P2 — 集成体验与边界文档

#### P2-1 `AccessContext` 仅 Servlet 层填充，MVC/自研端点需手工设置

- 证据：`AccessContextHolder.set(...)` 仅在组件两个 Servlet 中调用（`UploadServlet.java:137-145`、`DownloadServlet.java:103-108`）；core/MVC 路径为 `AccessContext.EMPTY`。
- 影响：使用 `endpoint.enabled=false`（纯 bean 模式）或自研 MVC 端点的宿主，拿不到 method/URI/IP/UA，需自行 `AccessContextHolder.set/clear`。
- 建议：文档补「自研端点如何填充 AccessContext」示例，或提供一个小型 `Filter`/工具方法（如 `AccessContexts.from(HttpServletRequest)`）降低样板。

#### P2-2 `trusted-upload-service.enabled` 未纳入 `UploadFileProperties`

- 证据：该开关由 `@ConditionalOnProperty(prefix="upload-file.trusted-upload-service", ...)` 直接读取（`UploadFileAutoConfiguration.java:326`），`UploadFileProperties` 中无对应字段。
- 影响：IDE 配置元数据（`spring-configuration-metadata.json`）不显示该键，宿主发现性差。
- 建议：纳入 `UploadFileProperties`（即使只用于条件判断），或补 `additional-spring-configuration-metadata.json`。

#### P2-3 `RedisQuotaStore` usage hash 无 TTL，依赖 `reconcile`/cleanup

- 证据：`RedisQuotaStore` 的 `usage` hash 与 `total` 无过期（`RedisQuotaStore.java:74-83`）；回收依赖启动 `reconcile`（`UploadFileAutoConfiguration.java:267`）与孤儿清理 `release`（`StorageCleanupService.java:336`）。
- 影响：若宿主既不重启也不跑 cleanup，残留计数不会自愈。
- 建议：文档明确「`quota.store=redis` 需保证 cleanup 启用或周期性 `reconcile`」，或为 usage hash 提供与 `cleanup.task-ttl` 对齐的可选整表 TTL。

#### P2-4 统一信封仅覆盖失败体（确认，非问题）

- 现状：成功体仍为各端点裸 JSON，仅失败体可经宿主 `UploadErrorRenderer` Bean 统一；rc.8 demo 已给最小示例（`EnterpriseWiringConfig.java:69-72`）。
- 建议：文档保持该边界说明即可；若未来支持成功体信封，需评估前端兼容（PathFinder 前端仅消费状态码/文本，可零改动）。

#### P2-5 生态项（ROADMAP，非本版范围）

- 内容寻址秒传、整文件 SHA-256、对象存储后端、tus、Prometheus 指标、上传完成 Webhook、病毒扫描钩子——均见 `docs/ROADMAP.zh-CN.md`。PathFinder 对「内容寻址秒传」「对象存储后端」有真实诉求。

## 6. 属性 / 行为对照（rc.7 → rc.8 收口确认）

| 属性 / 能力 | rc.7 状态 | rc.8 状态 |
|---|---|---|
| `QuotaStore.reconcile` | ✖ 无 SPI 方法 | ✔ 新增 `default`；`RedisQuotaStore` 覆写（Lua 原子重建） |
| `quota.store=redis` | ✖ 计数可能漂移、泄漏 | ✔ 启动自动对账 + 孤儿清理回收 |
| `RedisIdentifierLockProvider` | ✖ 固定 TTL 不续租 | ✔ watchdog 续租（HEAD 为 4 线程共享池） |
| `RedisTaskStore` 索引迁移 | ✖ 非原子 | ✔ Lua `RENAME` 原子迁移 |
| `RedisTaskStore.list()` | ✖ 单次 `MGET` | ✔ 分批 `MGET`（500/批） |
| 审计钩子 | ✖ 无 method/IP/UA | ✔ `AccessContext` + 6 参 `default` 重载 |
| `access-log` | ✖ 每分片一行 | ✔ 默认 `task`；首分片有界 LRU；`all` 恢复 |
| `TrustedUploadService` | ✖ 宿主自建 | ✔ starter 自动装配（可覆写/关闭） |
| 统一信封 | ◐ 能力有、示例缺 | ✔ demo 补最小示例 |
| `upload-file-bom` | ✖ 无 | ✔ 7 模块统一版本 |
| 成功体 / 端点 / 磁盘与元数据格式 / 既有属性键 | — | ✔ 不变（无 breaking API） |

## 7. 结论与优先级建议

rc.8 是一次**面向 GA 的正确性收尾版本**：把 rc.7 反馈的三条 P1（配额对账、合并未确认泄漏、分布式锁续租）与两条 P2（索引迁移原子性、`list()` 分批）全部闭环，并补齐 rc.6 顺延的审计上下文与 access-log 降噪，同时以 BOM + SOW/API 冻结 + 二进制兼容门禁把「发布工程」补齐。PathFinder 从 rc.7 升 rc.8 **仅需改坐标（改走 BOM）+ 删除一个自建 Bean + 覆写一个审计重载**，164 例测试全绿、前端零改动，升级成本极低而收益明确。

若组件继续投入（GA 后 `1.0.x / 1.1`），建议优先级：

1. **P1-1（未门控 API 收口完整性）**——`cancelUpload` 纳入 `TrustedUploadService`，剩余无门控读/写方法统一策略；这是 rc.7「受信读收口」的收尾，属 API 一致性问题；
2. **P1-2（授权决策缓存/门控指引）**——把 PathFinder 的 A1 做法标准化为可选装饰器 + 文档，直接降低 DB 型 `AccessControl` 实现方的每分片成本；
3. **P2-1（自研端点 AccessContext 填充）**——补 Filter/工具与文档，照顾 `endpoint.enabled=false` 的宿主；
4. **P2-2 / P2-3（配置元数据、Redis 配额 TTL/对账文档）**——集成体验与运维边界；
5. P2-5 生态项按 ROADMAP 节奏推进。

PathFinder 侧的可选跟进：

- A1 缓存 TTL（60s）与软删除语义的权衡已在 §4-1 说明；如未来多实例部署，需评估进程内缓存在多实例间的一致性（或改用 Redis 短 TTL 缓存）；
- 如需统一响应信封，提供 `UploadErrorRenderer` Bean 渲染 `ApiResponse`（rc.8 已支持，无需覆盖 Servlet）；
- 若未来启用 `quota.store=redis` + `lock.identifier-lock=redis`，依赖 rc.8 的自动对账与续租即可，无需自建对账任务。

---

*反馈整理：PathFinder 项目组 · 2026-09 · 依据组件仓 HEAD `8eb31ca`（`1.0.0-rc.8`）源码（core / servlet-jakarta / starter-jakarta / store-redis / bom）与 path-finder rc.8 集成工作区核对。*
