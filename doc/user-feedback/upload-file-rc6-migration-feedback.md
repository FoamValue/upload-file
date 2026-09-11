# upload-file 组件 rc.6 迁移评审反馈（供组件升级优化参考）

> 本文件由组件使用者（PathFinder 文件管理系统）整理，用于向 `cn.chenxinjie:upload-file`
> 反馈 **rc.6 迁移**的真实使用情况、利好、残留风险与增强诉求，供组件侧评估与升级优化。
> 本工程侧**不修改组件代码**；仅按现状如实反馈。
>
> 关联历史反馈：[upload-file-usage-feedback.md](upload-file-usage-feedback.md)（rc.3 基线）。

## 1. 评审对象与接入环境

| 项 | 内容 |
|---|---|
| 集成项目 | path-finder（文件管理系统，单组织私有部署） |
| 集成时点 | 组件 `1.0.0-rc.6`；path-finder 基线 commit `fec319e`（rc.4 生产基线）+ 工作区 rc.6 迁移改动 |
| 运行环境 | JDK 26 · Spring Boot 4.1.1 · Spring Security 7.1.1 · Redis 9（部署）/ 本地 Redis 7 · MySQL 8 |
| 实际引用产物 | `upload-file-spring-boot-starter-jakarta:1.0.0-rc.6` + `upload-file-store-redis:1.0.0-rc.6`（**已从 core 手工装配迁移到官方 HTTP 层**） |
| 迁移依据 | path-finder `docs/design/ADR-001-upload-file-starter-jakarta.md`、`docs/design/UPGRADE-upload-file-starter-jakarta.md`（C1~C8 已执行） |
| 端点形态 | `/upload` 由组件 `UploadServlet` 承载；`/download` 保持默认关闭（`endpoint.download-enabled=false`）；业务下载走 `/api/file/download/{token}` |
| 业务形态 | 分片（5MB）→ MD5 校验 → 断点续传 → 异步合并轮询 → confirm 入库 → 文件迁出组件存储进入业务统一存储 |
| 测试结果 | 迁移后 `mvn test` 全绿：**161 例 / 0 失败**（含新增 `UploadOwnerAccessControlTest`、`UploadAccessAuditListenerTest`、`UploadEndpointWiringTest`） |

---

## 2. rc.6 能力使用总览

图例：✔ 已用（生产） ｜ ◐ 部分/变通使用 ｜ ✖ 未使用 ｜ N/A 不适用

| rc.6 能力 | 使用 | 说明与证据 |
|---|---|---|
| `upload-file-spring-boot-starter-jakarta` 自动装配 | ✔ | 替代 `UploadFileConfig` 手工装配（删除 147 行） |
| 组件 `UploadServlet`（`/upload`） | ✔ | 替代自研 `UploadController`（删除 79 行）；协议/成功体不变，前端零改动 |
| `endpoint.upload-enabled` / `endpoint.enabled` | ✔ | `upload-enabled=true`；未用纯 bean 模式 |
| `endpoint.download-enabled` | ✔（关闭） | 保持 `false`（最小暴露），未新开裸下载面 |
| `AccessControl.decide()` → `AccessDecision.deny(403)` | ✔ | `UploadOwnerAccessControl` 覆写 `decide()`，越权 403（区分未认证 401） |
| `AccessControlListener`（审计钩子） | ✔ | 新增 `UploadAccessAuditListener`，决策点写 `FORBIDDEN success=0`（X1 迁移） |
| `UploadErrorCodes` 符号错误码 | ◐ | 通过 `legacy` 错误体间接体现；未用 `standard` |
| `UploadErrorRenderer` SPI（自定义信封） | ✖ | starter 未消费宿主 Bean，无法对接 `ApiResponse`（见 §5 P0-3） |
| `multipart.strategy` | ✔ | `component`（沿用 `max-chunk-size`/`max-request-size`） |
| `http.error-body` / `http.cancel-not-found-status` | ✔（默认） | `legacy` / `404` |
| `cleanup.*` + `RedisCleanupLock` | ✔ | `cleanup.enabled=true`、`use-redis-lock=true`（starter 消费） |
| `async-merge.*` 线程池配置化 | ✔ | `enabled=true`、`thread-pool-size=2`（starter 消费） |
| `quota.max-bytes`（507） | ◐ | 配置就位（默认 0 关闭） |
| 受信读重载 `getTask(id, token)` | ✖ | 服务端 confirm 仍用无 token 受信变体（内部调用） |
| `TaskStoreMigrator` | ✖ | 无场景 |
| 纯 Servlet 路径 `UploadFileContext` | ✖ | 使用 starter，非纯 Servlet |

---

## 3. rc.6 对 PathFinder 的利好（已兑现）

rc.6 的定位即「回应 path-finder 的 ADR-001/UPGRADE 评估」，四条历史阻塞被精准拆掉，迁移才成立：

| 维度 | rc.5 的阻塞 | rc.6 的解法 | 对 PathFinder 的价值 |
|---|---|---|---|
| 下载安全面 | starter 无条件注册 `/download` | `endpoint.download-enabled` **默认 false** | 最小暴露，业务下载不受影响，无需 nginx 新透传 |
| 鉴权语义 | `check()` 只能抛 401 | `decide()` → `AccessDecision.deny(403)` | 越权 403 与 MVC 路径一致，语义正确 |
| 审计 | 无决策钩子，只能靠 `@ControllerAdvice` | `AccessControlListener` 在决策点广播 | X1 审计从「异常出口」迁到「决策点」，Servlet/MVC 单点留痕、不重复 |
| 错误/上限契约 | 不可控 | `UploadErrorCodes`、`multipart.strategy` | 符号错误码 + 容器上限显式化 |

**架构层面的收益**：

1. **删除受控镜像代码**：`UploadFileConfig`(147 行) + `UploadController`(79 行) 共 226 行自研装配/协议镜像消失，HTTP 层由组件单点维护，消除「协议漂移」长期债。
2. **升级路径首次闭环**：ADR-001 把「不迁移」写成带撤销条件的决策，rc.6 触发条件 #2/#3 后按 UPGRADE 执行——**这是本次集成最有价值的工程实践**：决策文档 + 可触发撤销条件 = 让下一次升级有据可依，而非靠记忆。
3. **契约稳定、可回滚**：core 只增不删；任务元数据/磁盘布局在 rc.5/rc.6 两端一致，回滚仅需 `git revert` + 重启，无数据迁移。
4. **错误语义前移**：`UploadServlet` 对客户端可恢复错误返回 400/401/403/404/409/507，替代旧 `UploadController` 的「500 兜底」（呼应 rc.3 反馈 §4-6）。
5. **`AccessControl` 增量演进设计得当**：`decide()` 默认桥接 `check()`，旧实现零改动；新实现可只覆写 `decide()`，兼容性与表达力兼得。

---

## 4. 迁移后项目侧仍需盯住的风险

1. **错误体信封不再统一**：`/upload` 失败体为组件模型（`legacy`），不再是 `ApiResponse{code,message}`。前端兼容（只读状态码/文本），但后端统一信封被打破；若未来要恢复统一，因 starter 不消费 `UploadErrorRenderer` Bean，只能覆盖整个 Servlet Bean（见 §5 P0-3）。
2. **越权审计字段退化**：`AccessControlListener` 回调无 `HttpServletRequest`，`UploadAccessAuditListener` 落库的 method/uri 只有 `null` 与 `"/upload?action=..."`，IP/UA 缺失，与登录审计字段不一致。
3. **每分片一次鉴权 DB 查询**：`gate()`（`ResumableUploadService.java:147`）每个 chunk 调用一次 `decide()`，PathFinder 侧 `findFirstByUploadIdentifierAndDelFlag` 一次 DB 查询。500MB/5MB = 100 次/文件（迁移前同样存在，非回归）。建议 PathFinder 侧加 identifier→owner 短 TTL 缓存。
4. **多实例仍不完整**：`IdentifierLock` 为进程内锁，仅 cleanup 有 `RedisCleanupLock`；水平扩展时同 identifier 的上传/合并不互斥。
5. **`/download` 关闭后，组件临时产物无兜底下载入口**：如需联调兜底，需显式开启并自行评估 nginx 透传与鉴权（当前取舍为最小暴露，接受）。

---

## 5. 组件可提升项（按优先级，附证据）

### P0 — 正确性 / 资源泄漏（建议下个 rc 修复）

#### P0-1 `RedisTaskStore` 的 index 集合无限泄漏 ⚠️ 对本项目直接影响

- 证据：`upload-file-store-redis/.../RedisTaskStore.java:90-100` `save()` 用 `setex(key, ttl, json)` 让任务 key 过期，同时 `sadd(indexKey, identifier)`；但 `indexKey` **无 TTL、成员从不清理**。`list()`（`:114-117`）遇 `json==null` 仅 `continue`，不 `SREM`；`remove()`（`:108`）虽 `SREM`，但 key 已 TTL 过期时 `list()` 根本看不到它，永不触发。
- 影响：PathFinder 使用 `metadata-store=redis` + `ttl-seconds=86400`，**每次上传的 identifier 都永久留在 index**，长期运行 Redis 内存持续增长（且 index 越大 `list()` 越慢，放大 P0-2）。
- 建议：改用 Redis 7.4+ `HEXPIRE`（hash 存任务 + 成员级 TTL）；或 sorted-set 按 `updateTime` 索引；至少在 `list()`/cleanup 时对空 key 执行 `SREM` 修剪。

#### P0-2 `RedisTaskStore.list()` 为 N+1 查询

- 证据：`RedisTaskStore.java:114-135` `smembers(indexKey)` 后逐 identifier `GET`。
- 影响：`StorageCleanupService.cleanupExpiredTasks` / `cleanupOrphans` 每轮都调 `list()`，大 N 时阻塞并放大 Redis RTT。
- 建议：`MGET`/pipeline 批量读取，或 `SCAN` 流式；配合 P0-1 的 index 修剪。

#### P0-3 starter 不消费宿主 `UploadErrorRenderer` Bean（文档与实现不符）

- 证据：`UploadFileAutoConfiguration.java:324-331` `uploadFileServlet(ResumableUploadService, UploadFileProperties)` 无 renderer provider，`:329` 直接 `UploadErrorRenderers.from(properties.getHttp().getErrorBody())`；download 同构（`:360-366`）。实测 rc.6 字节码签名一致。
- 文档承诺：`README.zh-CN.md:350/366`、`README.md:369/385` 均称「需自有信封 → 提供 `UploadErrorRenderer` Bean」；`CHANGELOG.md:88` 亦称「SPI lets a host supply its own envelope」。
- 影响：Spring Boot 宿主无法通过 Bean 注入自定义信封（如 PathFinder 的 `ApiResponse`），只能用 `legacy`/`standard`，或整体覆盖 Servlet Bean——SPI 在 starter 路径形同虚设。
- 建议：auto-config 注入 `ObjectProvider<UploadErrorRenderer>`，存在则用于 servlet；并在 README 明确「starter 路径下提供 Bean 即生效」。

#### P0-4 `multipart.strategy=component` 默认无上限的 footgun

- 证据：`UploadFileAutoConfiguration.java:388-405`，`component` 分支 `MultipartConfigElement(null, properties.getMaxChunkSize(), properties.getMaxRequestSize(), ...)`；而两属性默认均为 `-1`（`UploadFileProperties.java:42`、`:45`）。
- 影响：宿主常只设服务层 `max-file-size`（如 500MB），容器层 `maxRequestSize` 仍无限 → 单请求可打爆堆/磁盘，构成 DoS 面。
- 建议：`maxRequestSize` 未显式设置时从 `max-file-size` 推导安全上限，或启动时对该组合告警。

### P1 — 多实例 / 一致性 / API 安全

#### P1-1 `IdentifierLock` 仅进程内，多实例上传/合并不互斥

- 证据：`IdentifierLock` 为内存实现，仅 `CleanupLock` 有 `RedisCleanupLock`（`cleanup.use-redis-lock`）。
- 建议：提供 `RedisIdentifierLock`，或在文档明确「多实例仅元数据共享，串行化需外部保证」。

#### P1-2 全局配额 `setMaxTotalBytes` 非原子

- check-then-act 竞态：并发上传可超 `quota.max-bytes`。
- 建议：Redis `INCRBY/DECRBY` 原子计数，或提供可插拔的 `QuotaStore` SPI。

#### P1-3 无 token 的受信重载是公开 API，误用无编译期保护

- 证据：`ResumableUploadService.java:324/345/374`（`getProgress(id)`、`isChunkUploaded(id,i)`、`getTask(id)`）不做鉴权，仅 Javadoc 警示。
- 建议：命名区分（如 `getTaskTrusted`）或拆到独立 `TrustedUploadService`，让越权误用在编译期可见。

#### P1-4 `AccessControl.check()` 默认抛 `UnsupportedOperationException`

- 证据：`AccessControl.java:58-84`：`decide()` 默认桥接 `check()`，而 `check()` 默认抛异常。实现类若两个都不覆写，**编译通过、运行期才炸**；且 rc.6 起 `AccessControl` 不再是函数式接口（lambda 用法会编译失败）。
- 建议：保留抽象 `check()`（rc.5 兼容）或提供 `AbstractAccessControl` 基类；在 Javadoc 明确「必须覆写其一」。

### P2 — 能力 / 可观测 / 生态

#### P2-1 审计钩子缺请求上下文

- `AccessControlListener.onDecision(identifier, action, decision, elapsedNanos)` 无法拿到 method/IP/UA，合规审计字段受限。
- 建议：签名带轻量 `AccessContext`，或提供 Servlet 层访问日志 Filter。

#### P2-2 `observability.access-log=true` 每分片一行

- `gate()` 每 chunk 都通知监听器，日志量 = 分片数，噪声大。
- 建议：按 identifier 聚合/采样，或仅记录 deny 与任务级事件。

#### P2-3 download 关闭时仍装配 `ResumableDownloadService`

- 证据：`UploadFileAutoConfiguration.java:205` 无条件创建该 Bean。
- 建议：用 `@ConditionalOnProperty` 与 `endpoint.download-enabled` 对齐，减少无谓 Bean。

#### P2-4 ROADMAP 已列、可作为差异化卖点加速的项

- Prometheus 指标、上传完成 Webhook、内容寻址秒传、整文件 SHA-256 内容校验、对象存储后端、tus 协议、病毒扫描钩子（建议在 `ChunkStorage`/合并完成事件预留扩展点）。

---

## 6. 属性 / 行为对照（rc.3 反馈 §8 的收口确认）

rc.3 反馈中列为「手工装配死配置」的键，rc.6 starter 下**已全部被消费**，配置即所得：

| 属性 | rc.3 手工装配 | rc.6 starter |
|---|---|---|
| `cleanup.enabled/run-on-startup/interval/task-ttl/orphan-enabled/use-redis-lock` | ✖ 死配置 | ✔ 生效（`StorageCleanupService` + `RedisCleanupLock`） |
| `async-merge.enabled/thread-pool-size` | ✖ 固定 2 线程 | ✔ 生效（`uploadFileAsyncMergeExecutor`） |
| `max-request-size` | ✖ 未消费 | ✔ 作 `@MultipartConfig.maxRequestSize` |
| `security.enabled/header-name` | ✖ 未消费 | ✔ 生效（本项目用自有 `AccessControl` Bean 覆盖） |
| `endpoint.*` / `http.*` / `multipart.strategy` | N/A（rc.6 新增） | ✔ 生效 |

> 遗留：`security.enabled=false` 时 starter 默认回退 `PermitAllAccessControl`，依赖宿主提供 `AccessControl` Bean 覆盖；本项目已由 `UploadOwnerAccessControl` 覆盖，但**建议 README 强化该组合的告警/校验**（如 `security.enabled=false` 且无宿主 `AccessControl` Bean 时启动 warn），避免端点裸奔。

---

## 7. 结论与优先级建议

rc.6 是一次高质量的上游协同：它让 PathFinder **用组件官方 HTTP 层替换了 226 行受控镜像代码，且安全面更小、审计更准、错误语义更精确**；组件的演进策略（只增不删 + `@ConditionalOnMissingBean` 可覆盖 + 撤销条件成文）值得沉淀为官方集成范式。

若组件继续投入，建议优先级：

1. **P0-1 / P0-2（Redis index 泄漏与 N+1）**——PathFinder 生产环境的真实隐患，`metadata-store=redis` 用户的共性问题；
2. **P0-3（starter 消费 `UploadErrorRenderer` Bean）**——文档承诺与实现不一致，影响所有需要业务信封的 Spring Boot 宿主；
3. **P0-4（multipart 默认无上限）**——安全默认值问题，修复成本低、收益广；
4. P1（多实例锁、原子配额、受信 API 命名）与 P2（审计上下文、指标/Webhook）按 ROADMAP 节奏推进。

PathFinder 侧的可选跟进：为 `UploadOwnerAccessControl` 增加 identifier→owner 短 TTL 缓存，降低每分片鉴权的 DB 压力；如需统一信封，评估「覆盖 `uploadFileServlet` Bean 注入自定义 renderer」或等待组件 P0-3 修复。

---

*反馈整理：PathFinder 项目组 · 2026-09 · 依据 upload-file `1.0.0-rc.6` 源码（core/servlet-jakarta/starter-jakarta/store-redis）与 path-finder 迁移工作区核对。*
