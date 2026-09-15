# upload-file 组件 rc.7 使用反馈（供组件升级优化参考）

> 本文件由组件使用者（PathFinder 文件管理系统）整理，用于向 `cn.chenxinjie:upload-file`
> 反馈 **rc.7** 的真实使用情况、利好兑现、残留风险与增强诉求，供组件侧评估与升级优化。
> 本工程侧**不修改组件代码**；仅按现状如实反馈。
>
> 关联历史反馈：
> - [upload-file-usage-feedback.md](upload-file-usage-feedback.md)（rc.3 基线，core 手工装配）
> - [upload-file-rc6-migration-feedback.md](upload-file-rc6-migration-feedback.md)（rc.6 迁移评审）

## 1. 评审对象与接入环境

| 项 | 内容 |
|---|---|
| 集成项目 | path-finder（文件管理系统，单组织私有部署） |
| 集成时点 | 组件 `1.0.0-rc.7`；path-finder 基线 commit `fec319e`（rc.4 生产基线）+ 工作区 rc.7 迁移改动 |
| 运行环境 | JDK 26 · Spring Boot 4.1.1 · Spring Security 7.1.1 · Redis 9（部署）/ 本地 Redis 7 · MySQL 8 |
| 实际引用产物 | `upload-file-spring-boot-starter-jakarta:1.0.0-rc.7` + `upload-file-store-redis:1.0.0-rc.7`（starter 自动装配 + 官方 Servlet） |
| 迁移依据 | path-finder `docs/design/ADR-001-upload-file-starter-jakarta.md`、`UPGRADE-upload-file-starter-jakarta.md`（C1~C8）、`PLAN-PathFinder-v1.0.0.md` §15（PF-801~PF-805） |
| 端点形态 | `/upload` 由组件 `UploadServlet` 承载；`/download` 保持默认关闭（`endpoint.download-enabled=false`）；业务下载走 `/api/file/download/{token}` |
| 业务形态 | 分片（5MB）→ MD5 校验 → 断点续传 → 异步合并轮询 → confirm 按 `finalPath` 迁库 → `cancelUpload` 回收 |
| 测试结果 | `mvn test` 全绿：**161 例 / 0 失败**；`/upload` 契约与成功体不变，前端零改动 |

组件接入点（rc.7 相对 rc.6 的差异）：

- `server/pom.xml:56-64`：`upload-file-spring-boot-starter-jakarta` / `upload-file-store-redis` 由 rc.6 升至 **rc.7**；
- `config/UploadTrustedConfig.java:20`：新增 `TrustedUploadService` Bean（rc.7 受信读门面，starter 不自动装配）；
- `service/FileService.java:316`：confirm 由 `getTask(id)`（rc.7 起 `@Deprecated`）改用 `trustedUploadService.getTask(identifier).finalPath`，`:344` 迁库后 `cancelUpload`；
- `application.yml:50-98`：`lock.*` / `quota.store` 保持默认（单实例），在注释中登记 rc.7 可选值；
- `security/UploadOwnerAccessControl.java:33`、`UploadAccessAuditListener.java:31`：沿用 rc.6 的 `decide()`/审计钩子，未改动。

---

## 2. rc.7 能力使用总览

图例：✔ 已用（生产） ｜ ◐ 部分/变通使用 ｜ ✖ 未使用 ｜ N/A 不适用

| rc.7 能力 | 使用 | 说明与证据 |
|---|---|---|
| `upload-file-spring-boot-starter-jakarta:1.0.0-rc.7` | ✔ | 坐标升级，装配方式不变 |
| `RedisTaskStore` ZSET 索引 + 懒迁移 | ✔ | `metadata-store=redis`；升级后无需手工迁移（旧 `SET` 索引首次读写自动迁移） |
| `RedisTaskStore.list()` 单次 `MGET` | ✔ | 由 `StorageCleanupService` 每轮调用，N+1 消失 |
| starter 消费宿主 `UploadErrorRenderer` Bean | ◐ | rc.7 已具备能力；本工程仍用 `legacy`（见 §5 P2-4） |
| `multipart` 安全默认（请求上限推导） | ✔（无感） | 已显式设 `max-request-size=10485760`，不受 breaking-default 影响 |
| `lock.identifier-lock` + `RedisIdentifierLockProvider` | ✖ | 保持默认 `local`（进程内）；单实例部署 |
| `quota.store` + `RedisQuotaStore` | ✖ | 保持默认 `task-store`；`quota.max-bytes=0`（关闭） |
| `TrustedUploadService`（受信读收口） | ✔ | 宿主自建 Bean（`UploadTrustedConfig`），confirm 走受信读 |
| `AbstractAccessControl` 基座 | ✖ | 本工程直接 `implements AccessControl` 覆写 `decide()` |
| `AccessControl.ofDecide/ofCheck` | ✖ | 非函数式实现，未用 |
| `getTask(id, token)` / `isChunkUploaded(id, index, token)` 门控读 | ✖ | 服务端受信读走门面；HTTP 边界由组件 Servlet 内部门控 |
| starter 安全默认告警（`security.enabled=false` 且无 `AccessControl` Bean） | ✔（无感） | 本工程提供 `UploadOwnerAccessControl` Bean，不触发 WARN |
| `ResumableDownloadService` `@Lazy` | ✔（无感） | `/download` 关闭，Bean 不再于启动期构建 |
| 下载统一错误契约 + 单次访问决策 | N/A | `/download` 默认关闭，未暴露 |
| `cleanup.*` + `RedisCleanupLock` | ✔ | `cleanup.enabled=true`、`use-redis-lock=true` |
| `observability.access-log` | ✖ | `false`；越权审计由 `UploadAccessAuditListener` 落库 |

---

## 3. rc.7 对 PathFinder 的利好（已兑现）

rc.7 的定位即「逐条收口 [rc.6 迁移反馈](upload-file-rc6-migration-feedback.md) 的 P0/P1」。核对源码与运行，五条历史阻塞均已闭环：

| 维度 | rc.6 的阻塞 | rc.7 的解法 | 对 PathFinder 的价值 |
|---|---|---|---|
| 存储正确性 | `RedisTaskStore` 索引 `SET` 无 TTL、成员永不清理，`metadata-store=redis` 长期运行内存持续增长 | 索引改按写入时间打分的 `ZSET`，`list()` 先按 TTL `ZREMRANGEBYSCORE` 修剪、再对已消失 key 惰性 `ZREM` | PathFinder 生产 `metadata-store=redis` + `ttl-seconds=86400`，索引不再无界增长（rc.6 反馈 P0-1 消除） |
| 查询效率 | `list()` 对每个 identifier 逐次 `GET`（N+1），cleanup 每轮放大 RTT | 改为单次 `MGET`（`RedisTaskStore.java:178`） | cleanup/任务枚举更轻，Redis RTT 由 N 降为 1 |
| 错误信封 | starter 不消费宿主 `UploadErrorRenderer` Bean，文档承诺与实现不符 | 两个 Servlet Bean 注入 `ObjectProvider<UploadErrorRenderer>`，宿主 Bean 优先、缺省回落 `legacy`/`standard`（`UploadFileAutoConfiguration.java:411-418`、`:448-457`） | 若未来要统一 `ApiResponse` 信封，现在提供 Bean 即生效（rc.6 反馈 P0-3 消除） |
| 安全默认 | `multipart.strategy=component` 且 `max-request-size` 未设时容器上限为无限，构成 DoS 面 | 未显式设置时按 `max-chunk-size`（否则 `max-file-size`）推导有界上限；三者皆空仍无上限但输出 WARN（`:497-510`） | 默认即安全；PathFinder 已显式设值，行为不变（rc.6 反馈 P0-4 消除） |
| 多实例 / 配额 | `IdentifierLock` 仅进程内；`setMaxTotalBytes` 为 check-then-act 竞态 | 新增 `IdentifierLockProvider`/`RedisIdentifierLockProvider` 与 `QuotaStore`/`RedisQuotaStore`（Lua 原子 check-and-reserve） | 水平扩展路径打开（本工程当前单实例未启用，见 §5） |

**架构层面的收益**：

1. **受信读 API 在编译期可见**（rc.6 反馈 P1-3）：`ResumableUploadService.getTask(id)` / `isChunkUploaded(id,index)` 标 `@Deprecated`（`ResumableUploadService.java:419-421`、`:379-381`），无门控读隔离到只读门面 `TrustedUploadService`。PathFinder 的 `FileService` confirm 因此从「公开无门控读」迁到 `trustedUploadService.getTask(...)`，越权误用不再依赖 Javadoc 提醒，迁移后无编译期弃用告警。
2. **`AccessControl` 演进承诺兑现**（rc.6 反馈 P1-4）：`check()` 改 `default`（不再抽象、不再强制实现），新增 `AbstractAccessControl` 基座与 `ofDecide/ofCheck` 函数式写法；PathFinder 直接覆写 `decide()` 的实现零改动。
3. **安全默认不再静默**：`security.enabled=false` 且无宿主 `AccessControl` Bean 时启动 WARN（`UploadFileAutoConfiguration.java:125-135`），`security.enabled=true` 但 token 为空仍 fail-fast；PathFinder 由 `UploadOwnerAccessControl` Bean 覆盖，安全前提显式可见。
4. **下载端点错误契约与门控收敛**：`/download` 的 400/404/416/401/403 改为 JSON 失败体（携带符号码），一次请求只做一次访问决策。PathFinder 虽关闭 `/download`，但该收敛消除了 rc.6 反馈中「下载错误体不统一、重复门控」的隐患，未来开启即用。
5. **启动期无谓 Bean 消除**：`ResumableDownloadService` 改 `@Lazy`（`UploadFileAutoConfiguration.java:284`），`/download` 关闭时不再于启动期构建（rc.6 反馈 P2-3）。
6. **`RedisTaskStore` 升级零运维**：旧 `SET` 索引在首次读写时懒迁移为 `ZSET`（`RedisTaskStore.java:92-110`），PathFinder 升级 rc.7 未做任何 Redis 数据操作。

---

## 4. 迁移后项目侧仍需盯住的风险

1. **breaking-default 的边界已确认无影响**：rc.7 唯一的 breaking 默认是 `multipart.strategy=component` 下 `max-request-size` 的推导。PathFinder 在 `application.yml:62` 显式设 `max-request-size=10485760`（5MB 分片 + 表单开销），命中「显式设置者行为不变」分支，容器上限逐字节不变。
2. **越权审计字段仍退化**：`AccessControlListener.onDecision(identifier, action, decision, elapsedNanos)`（`AccessControlListener.java:28`）不携带 `HttpServletRequest`，`UploadAccessAuditListener` 落库的 method 只有 `null`、uri 只能拼成 `"/upload?action=..."`，IP/UA 缺失，与登录审计字段不一致（rc.6 反馈 P2-1，rc.7 明确顺延 rc.8）。
3. **每分片一次鉴权 DB 查询**：`gate()`（`ResumableUploadService.java:172`）每个 chunk 调用一次 `decide()`，PathFinder 的 `UploadOwnerAccessControl.decide()` 触发一次 `findFirstByUploadIdentifierAndDelFlag` DB 查询。500MB/5MB = 100 次/文件。**建议 PathFinder 侧加 identifier→owner 短 TTL 缓存**（组件侧行为与 rc.6 一致，非回归）。
4. **`observability.access-log` 仍按分片输出**：`gate()` 每 chunk 通知监听器，日志量 = 分片数；PathFinder 保持 `false` 规避噪声（rc.6 反馈 P2-2，rc.8 处理）。
5. **`/download` 关闭后组件临时产物无兜底下载入口**：维持最小暴露取舍；如需联调兜底需显式开启并自行评估 nginx 透传与鉴权。

---

## 5. 组件可提升项（按优先级，附证据）

### P0 — 无

rc.7 未发现影响 PathFinder 生产数据安全的新 P0；rc.6 的四条 P0 已全部闭环。

### P1 — 配额 / 多实例的正确性（RedisQuotaStore 与 Redis 锁的边界）

#### P1-1 `RedisQuotaStore` 无自动对账，starter 装配后从不调用 `reconcile(TaskStore)` ⚠️

- 证据：`RedisQuotaStore.reconcile(TaskStore)` 定义于 `RedisQuotaStore.java:113`，类文档称「call it at startup or from the cleanup scheduler」（`:28`），但全仓（starter/core/store-redis 的 `src/main`）**除定义处外无任何调用**；`UploadFileAutoConfiguration.java:233-245` 仅 `new RedisQuotaStore(...)` 并注入上传/清理服务，未触发对账。
- 影响：`RedisQuotaStore` 的 `total` 计数器与 per-identifier hash 是独立于 `TaskStore` 的状态。Redis 实例重启/数据丢失（`total` 与 hash 一并归零）而 `TaskStore` 数据仍在（如使用 `metadata-store=jdbc` 或持久化任务）时，配额被**低估**，并发上传可超 `quota.max-bytes`；反之若 hash 残留而任务已不在，配额被**高估**。
- 建议：starter 在 `RedisQuotaStore` 装配后于启动期（或 `cleanup.run-on-startup` 时）调用一次 `reconcile(taskStore)`；或把 `reconcile` 提升为 `QuotaStore` SPI 的可选方法并由清理调度定期触发。

#### P1-2 「已合并未确认」任务的配额永不释放，`RedisQuotaStore` 计数永久泄漏 ⚠️

- 证据：`StorageCleanupService.cleanupExpiredTasks` 对已合并任务直接跳过（`StorageCleanupService.java:241` `if (task.isMerged()) continue;`）；`quotaStore.release(identifier)` 仅在 `cancelUpload`（`ResumableUploadService.java:647`）与过期任务删除（`StorageCleanupService.java:256-258`）时调用。
- 影响：合并成功但业务未 confirm（或 confirm 前服务重启/任务被 Redis TTL 淘汰）时，`RedisTaskStore` 的任务 key 到期后 `list()` 不再返回该任务，`release` 永不触发；而 `RedisQuotaStore` 的 hash/total **无 TTL**，该 identifier 的 `fileSize` 声明永久计入 `total`。长期运行下配额被逐步「吃掉」，表现为「明明没有文件却报 507」。
- 关联：rc.3 反馈 §4-2 的「合并产物孤儿」在磁盘侧由 `cleanup.orphan-enabled` 兜底，但**配额计数侧无对应兜底**。
- 建议：为 `RedisQuotaStore` 的 per-identifier 记录加 TTL（或按写入时间打分的 `ZSET` + 定期对账），并在 cleanup 的孤儿扫描中，对「无任务记录」的 identifier 一并 `release`；文档明确「`quota.store=redis` 必须周期性 `reconcile` 或依赖 cleanup 回收」。

> 说明：PathFinder 当前 `quota.max-bytes=0`（`application.yml:64`，配额关闭），`tryReserve` 直接返回 true 不记账，故上述 P1-1/P1-2 对本工程**暂不触发**；一旦启用 `quota.store=redis` 即暴露，属 `metadata-store=redis` 用户的共性问题。

#### P1-3 `RedisIdentifierLockProvider` 无续租/watchdog，`lock.ttl` 固定，长合并可能锁失效

- 证据：`RedisIdentifierLockProvider.lock()` 以 `SET key token NX PX ttlMillis` 获取锁（`RedisIdentifierLockProvider.java:73`），`ttlMillis` 由构造参数固定（`:48`），持有期间**不续租**；默认 `ttl=30s`（`UploadFileProperties.java:384`），`acquire-timeout=10s`（`:380`）。`ResumableUploadService.merge()` 全程持有同一 identifier 锁（`ResumableUploadService.java:463`）。
- 影响：大文件合并（数百 MB，尤其对象存储/慢盘）耗时超过 `lock.ttl` 时，锁自动过期；另一实例可同时获得同 identifier 锁，导致跨实例并发 merge / 新分片写入，破坏「per-identifier 串行化」承诺。
- 建议：合并等长操作期间按 `ttl/3` 续租（watchdog）；或在文档中要求 `lock.ttl` 必须大于最坏合并耗时并给出量级指引；或提供「获取锁后执行长任务」的 `try-with-resources` 续租包装。

### P2 — 一致性与集成体验

#### P2-1 `RedisTaskStore` 索引懒迁移非原子

- 证据：`ensureIndexMigrated` 执行 `SMEMBERS` → `DEL` → `ZADD`（`RedisTaskStore.java:98-107`），非原子；多实例同时启动迁移、或迁移期间有 `save()` 并发写入时，可能丢失条目（新写入的 identifier 被 `DEL` 掉且未进入 `ZADD`）。
- 建议：用 Lua 脚本或 `RENAME` + 临时 key 原子化迁移；或迁移期间以 `WATCH/MULTI` 保护。

#### P2-2 `RedisTaskStore.list()` 单连接 `MGET` 大 N 仍可能阻塞

- 证据：`list()` 在一条 Jedis 连接上构造全量 `byte[][]` 后一次 `MGET`（`RedisTaskStore.java:174-178`）。相比 N+1 已大幅改善，但 identifier 数量极大时单次命令仍会占用连接与 Redis 单线程时间。
- 建议：按批（如 500/批）`MGET`，或对超大 index 用 `ZSCAN` 流式处理。

#### P2-3 `TrustedUploadService` 未由 starter 自动装配

- 证据：`TrustedUploadService` 为 core 的 `final` 只读门面（`TrustedUploadService.java:27`），starter 未提供对应 Bean；PathFinder 需自建 `UploadTrustedConfig`（`UploadTrustedConfig.java:20`）才能走受信读。
- 建议：starter 以 `@ConditionalOnMissingBean` 暴露 `TrustedUploadService`（可加注释说明「无门控，仅供服务端受信流程」），降低宿主重复样板；若担心误用，可提供 `@ConditionalOnProperty` 显式开关。

#### P2-4 统一响应信封仍可选择跟进

- 现状：PathFinder 保持 `http.error-body=legacy`，前端对组件端点单独判 `resp.ok`、不套用 `ApiResponse{code,message,data}`。
- rc.7 已支持宿主 `UploadErrorRenderer` Bean 优先（`UploadFileAutoConfiguration.java:411-418`），PathFinder 可提供一个渲染 `ApiResponse` 的 Bean 即统一信封，无需整体覆盖 Servlet Bean（这是 rc.6 反馈 P0-3 的直接红利）。
- 建议：组件在 README/API 文档补一个「宿主 `UploadErrorRenderer` Bean → 统一信封」的最小示例，降低接入方决策成本。

#### P2-5 生态项（ROADMAP，非本版范围）

- 内容寻址秒传、整文件 SHA-256 内容校验、多租户命名空间、单用户配额/限速、对象存储后端、tus 协议、Prometheus 指标、上传完成 Webhook、病毒扫描钩子——均见 `docs/ROADMAP.zh-CN.md`。PathFinder 对「内容寻址秒传」「对象存储后端」有真实诉求，可作为差异化卖点优先排期。

---

## 6. 属性 / 行为对照（rc.6 反馈遗留项的收口确认）

| 属性 / 能力 | rc.6 状态 | rc.7 状态 |
|---|---|---|
| `RedisTaskStore` 索引 | ✖ 无界增长 | ✔ ZSET + TTL 修剪 + 惰性 ZREM |
| `RedisTaskStore.list()` | ✖ N+1 `GET` | ✔ 单次 `MGET` |
| starter 消费 `UploadErrorRenderer` Bean | ✖ 不消费（文档不符） | ✔ `ObjectProvider` 注入，宿主 Bean 优先 |
| `multipart` 请求上限 | ✖ 未设即无上限 | ✔ 按 chunk/file 推导；三空 WARN |
| `IdentifierLockProvider` / `lock.*` | ✖ 仅进程内 | ✔ SPI + `RedisIdentifierLockProvider`（`local|redis`） |
| `QuotaStore` / `quota.store` | ✖ check-then-act 竞态 | ✔ SPI + `RedisQuotaStore`（Lua 原子，`task-store|redis`）；**自动对账缺失见 §5 P1-1** |
| `getTask(id)` / `isChunkUploaded(id,index)` | 无门控公开 API | ✔ `@Deprecated` + `TrustedUploadService` 收口 |
| `AccessControl.check()` | 抽象、必须实现 | ✔ `default`，新增 `AbstractAccessControl` / `ofDecide` / `ofCheck` |
| 安全默认 | ✖ `security.enabled=false` 静默裸奔 | ✔ 无宿主 `AccessControl` Bean 时启动 WARN |
| `ResumableDownloadService` | ✖ 无条件构建 | ✔ `@Lazy`，`/download` 关闭时不构建 |
| 下载错误体 / 门控 | 容器 `sendError` HTML、重复门控 | ✔ JSON 失败体 + 符号码、单次决策 |
| `AccessControlListener` 请求上下文 | ✖ 无 method/IP/UA | ✖ 仍无（顺延 rc.8） |
| `access-log` 分片级降噪 | ✖ 每分片一行 | ✖ 仍按分片（顺延 rc.8） |

---

## 7. 结论与优先级建议

rc.7 是一次**高质量的收口版本**：它把 rc.6 迁移反馈的 P0（Redis 索引泄漏、N+1、renderer Bean、multipart 安全默认）与 P1（多实例锁、原子配额、受信读命名、`AccessControl` 基座）逐条落地，且保持「只增不删 + `@ConditionalOnMissingBean` 可覆盖 + 懒迁移零运维」的兼容策略。PathFinder 从 rc.6 升 rc.7 **仅需改坐标 + 新增一个 `TrustedUploadService` Bean + 迁移一处弃用调用**，161 例测试全绿、前端零改动，升级成本极低而收益明确。

若组件继续投入，建议优先级：

1. **P1-1 / P1-2（配额对账与合并未确认任务的释放）**——`quota.store=redis` 用户的正确性隐患，与 rc.7 刚修复的「索引泄漏」同域，修复后配额能力才算真正可用；
2. **P1-3（分布式锁续租）**——多实例路径的最后一块正确性短板，配合文档给出 `lock.ttl` 与最坏合并耗时的关系；
3. **P2-1 / P2-2（索引迁移原子性、`list()` 分批）**——存储层健壮性打磨；
4. **P2-3 / P2-4（`TrustedUploadService` 自动装配、统一信封示例）**——降低宿主样板与决策成本；
5. P2-5 生态项（秒传、SHA-256、对象存储、Webhook、指标）按 ROADMAP 节奏推进。

PathFinder 侧的可选跟进：

- 为 `UploadOwnerAccessControl` 增加 identifier→owner 短 TTL 缓存，降低每分片鉴权的 DB 压力；
- 如需统一响应信封，提供 `UploadErrorRenderer` Bean 渲染 `ApiResponse`（rc.7 已支持，无需覆盖 Servlet）；
- 若未来多实例部署，启用 `lock.identifier-lock=redis` + `quota.store=redis`，并**同时建立配额对账任务**（在 §5 P1-1 修复前自行调用 `reconcile`）。

---

*反馈整理：PathFinder 项目组 · 2026-09 · 依据 upload-file `1.0.0-rc.7` 源码（core / servlet-jakarta / starter-jakarta / store-redis）与 path-finder rc.7 迁移工作区核对。*
