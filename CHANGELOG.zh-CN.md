# 更新日志

本文件记录项目的所有重要变更。

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 🇺🇸 [English](CHANGELOG.md)

## [Unreleased]

针对 rc.6 的文档/实现一致性修复（审查发现）。

### 修复

- **`AccessControl.check()` 改为 `default` 方法**：新实现现在可以只覆写 `decide(...)`，无需再实现已废弃的
  `check(...)`（此前 `check()` 为抽象方法，与「增量桥接」承诺不符）。既有只覆写 `check()` 的实现行为不变。
  注意：`AccessControl` 因此**不再是函数式接口**，原先用 lambda 实现 `check()` 的写法需改为匿名类或改覆写
  `decide(...)`（`AccessControlListener` 仍为函数式接口，不受影响）。
- **下载端点纳入统一错误契约**：`DownloadServlet` 的 `400`/`404`/`416`/`401`/`403` 失败改为返回 JSON 失败体
  （由 `http.error-body` 选择形态），并携带符号错误码；此前使用容器 `sendError`（HTML 页），
  `RANGE_NOT_SATISFIABLE` 目录码从未被真正发出。
- **下载一次请求只做一次访问决策**：消除 `resolveFile` 与 `resolveFileName` 的重复门控（此前一次下载触发
  两次 `AccessControlListener` 事件、策略执行两次）。

### 新增

- **带访问门控的读接口**：`ResumableUploadService.getTask(identifier, token)` 与
  `isChunkUploaded(identifier, index, token)`；原无 token 版本保持不变，并在 Javadoc 中明确其**不执行**
  访问门控，供可信的服务端 confirm 流程使用。
- **端点 Bean 可覆盖**：`uploadFileServlet` / `downloadFileServlet` 作为 Bean 暴露，宿主可按类型覆写 Servlet
  实例，或按 Bean 名 `uploadFileServletRegistration` / `downloadFileServletRegistration` 覆写注册。
- **纯 Servlet 访问可观测**：`UploadFileContext` 新增 init-param `observability.access-log`，为上传/下载服务
  注册结构化访问日志监听器，行为与 starter 的 `observability.access-log` 一致。
- **企业化接线示例**：`example/upload-file-boot4-demo` 新增 `EnterpriseWiringConfig`（`enterprise` profile），
  演示覆写 `decide()` 返回 `AccessDecision.deny(403, ...)` 与 `AccessControlListener` 审计。

### 构建

- **发布修复**：父 POM 的 `maven.deploy.skip=true` 仅应作用于聚合器，但会被所有模块继承；现为 7 个库模块
  （core/servlet/servlet-jakarta/starter/starter-jakarta/store-jdbc/store-redis）显式覆盖为 `false`，确保
  `mvn deploy` 真正发布库产物（此前可能被继承值整体跳过）。
- **CI**：新增 GitHub Actions（JDK 17/21 矩阵，全量 `mvn verify`）。

### 文档

- README（中/英）顶部新增 rc.6 breaking 默认升级提示；特性列表补齐 rc.6 能力；澄清纯 Servlet init-param
  与 Spring 属性**并非同名**（`max-chunk-size` ↔ `chunk.max-size`、`http.cancel-not-found-status` ↔
  `cancel-not-found-status`）。
- API（中/英）补充下载端点错误体与符号码、两个端点的渲染器一致性说明。
- README（中/英）新增「自研 MVC 端点 → 官方 Servlet」迁移向导（坐标/差异矩阵、breaking 默认一行配置、
  `check()`→`decide()` 迁移片段、最小暴露建议），并说明 `upload-file-store-jdbc`/`redis` 为 optional 依赖、
  需显式引入。

## [1.0.0-rc.6] - 2026-09-05

**HTTP 层商业化可接入版本（安全与审计对齐）。** 解决 path-finder ADR-001/UPGRADE 评估结论（`-jakarta` 产物
只是 javax starter 的 drop-in，而非「core 手工装配 + 自研 MVC 端点」方案的 drop-in）。官方 servlet/starter
HTTP 层现可被商业系统直接采用：端点可控（含 `/download` 默认关闭）、`AccessControl` 增量式决策返回 + 审计钩子、
符号错误码 + 可选的统一错误体、multipart 策略化。

### 新增

- **端点注册可控**：`upload-file.endpoint.enabled`（默认 `true`；`false` = 纯 bean 模式，只装配服务 Bean、
  不注册 Servlet）、`endpoint.upload-enabled`（默认 `true`）、`endpoint.download-enabled`
  （**默认 `false`**——不显式开启则不再注册下载 Servlet）。两个注册 Bean 均可被同名 Bean 覆盖。
- **决策返回式 `AccessControl`（增量）**：新增默认方法 `decide(...)` 返回 `AccessDecision`
  （`allow()` / `deny(status, reason)`）；旧 `void check(...)` 保留并标 `@Deprecated`、经默认桥接，
  既有实现零改动。`AccessDeniedException` 支持可选 status（默认 `401`），宿主可区分 `401`/`403`。
- **审计钩子**：可选 `AccessControlListener` SPI，在上传/下载服务的每个入口（放行/拒绝 + 决策耗时）触发；
  MVC 与 Servlet 路径事件一致。内置结构化访问日志由 `upload-file.observability.access-log` 开启
  （默认 `false`）。
- **符号错误码**：`UploadErrorCode.code()` 返回显式 `UploadErrorCodes` 目录中的稳定码（始终可用、非类名派生）。
- **统一错误体（可选）**：`upload-file.http.error-body=standard` 将所有失败渲染为
  `UploadHttpError{code,status,message,identifier,action}`；默认 `legacy` 保持 rc.5 端点专用模型逐字节一致。
  `UploadErrorRenderer` SPI 支持宿主自定信封。
- **multipart 策略**：`upload-file.multipart.strategy`（`component` | `spring` | `unlimited`，默认
  `component` = rc.5 行为）。`spring` 跟随 `spring.servlet.multipart.*` / `spring.http.multipart.*`
  （Boot 默认 1MB/10MB）；`unlimited` 关闭容器上限。
- **可选取消语义**：`upload-file.http.cancel-not-found-status=200` 将取消不存在任务视为幂等 `200`
  （默认 `404`）。

### 变更（breaking 默认，已标注）

- `GET /upload` 必须有已知 `action`：缺失或未知 action 返回 `400`（`MISSING_ACTION` /
  `UPLOAD_UNKNOWN_ACTION`），不再当作 progress。
- merge/cancel/status/progress 的非 `UploadErrorCode` 服务端故障（及非 `IllegalArgumentException` 客户端
  错误）返回 `500`，不再吞成 `400`。合并大小不符、异步合并未开启改为带稳定码的 `UploadValidationException`
  （`400`）。
- `/download` 默认不再注册（见上）——恢复请设 `upload-file.endpoint.download-enabled=true`；README 顶部已
  置顶提示。

### 兼容性

- `AccessControl` 保持增量——Boot 2 / javax 手工装配使用方 rc.6 无需改代码。
- 其余新能力全部 off-by-default；成功体、既有属性与端点不变。
- 无磁盘布局/数据格式变化；升级仅重启。

## [1.0.0-rc.5] - 2026-09-04

**Jakarta / Spring Boot 4 版本。** 消除使用方反馈（`doc/user-feedback/upload-file-usage-feedback.md`，P0-1）
中最后一个顶层集成阻塞：官方 servlet 与 starter 产物基于 `javax.servlet`，导致 Spring Boot 3/4 使用方被迫
降级为 core 手工装配。rc.5 发布 `-jakarta` 孪生产物（FQCN 与 `upload-file.*` 属性一致，源码级无缝替换），
另附 Boot 4 演示。仅为打包层增量——无 core API / SPI 变更。

### 新增

- **`upload-file-servlet-jakarta`**：`upload-file-servlet` 的 Jakarta Servlet 5/6（`jakarta.servlet`）孪生版
  （`UploadFileContext` / `UploadServlet` / `DownloadServlet`，FQCN 与行为一致——只换 Maven 坐标，不改代码）。
- **`upload-file-spring-boot-starter-jakarta`**：starter 的 Spring Boot 4.0.0+（3.x 预期兼容）孪生版：
  FQCN、`upload-file.*` 属性集与默认值完全一致，经 Spring Boot 的 `AutoConfiguration.imports` 文件注册
  （取代 `spring.factories`）。可无缝替换 `upload-file-spring-boot-starter`。
- **`example/upload-file-boot4-demo`**：基于 jakarta starter 的 Spring Boot 4.0.0+ 演示（JDK 17+），在真实
  Boot 4 运行时验证完整续传流程（分片上传 → 暂停/续传 → `mergeAsync`/`mergeStatus` → 经
  `getTask(...).getFinalPath()` confirm → Range 下载）与 `action=cancel`。
- Boot 2 演示（`example/upload-file-demo`）新增异步合并与 `action=cancel` 走查。

### 变更

- 版本号升至 `1.0.0-rc.5`；jakarta 模块与 Boot 4 demo 纳入同一 reactor。读取 Servlet 6 / Boot 4 类文件使
  **全量根构建需 JDK 17+**；各产物字节码仍为 `--release 8`，JDK 8 使用方以
  `mvn install -pl upload-file-core,upload-file-servlet,upload-file-spring-boot-starter -am` 构建 javax 子集。
- README / docs/API / docs/DESIGN / docs/ROADMAP 补充 javax↔jakarta 产物矩阵、Boot 4.0.0+ 快速开始、
  JDK 17 构建基线与坐标替换升级路径——见 [V1.0.0-rc.5 任务开发计划](docs/PLAN-V1.0.0-rc.5.zh-CN.md)。

### 兼容性

- `javax` 产物（`upload-file-servlet`、`upload-file-spring-boot-starter`）不变——Boot 2 / Servlet 3.1 使用方
  坐标与行为不变。
- `-jakarta` 孪生产物为无缝替换，但 `javax` 产物与其 `-jakarta` 孪生版**不可同存于同一 classpath**——二选一。
- rc.5 无 SPI / core API 变更，也不新增任何 `upload-file.*` 配置项；Boot 4 上的 core 手工装配继续可用，
  且因 jakarta starter 的发布而变为可选。

## [1.0.0-rc.4] - 2026-09-03

**反馈驱动版本。** 依据真实使用方集成反馈（`doc/user-feedback/upload-file-usage-feedback.md`，
path-finder commit `62ae062`）补齐：confirm 阶段的稳定读/取消契约、core 异常稳定 HTTP 语义，
以及清理/手工装配语义的文档化。

### 新增

- **按 identifier 稳定读取**：`ResumableUploadService.getTask(identifier)` 返回当前任务，其
  `finalPath` 是 confirm 阶段合并产物的权威定位，无需再猜目录结构。
- **显式取消任务**：`ResumableUploadService.cancelUpload(identifier [, token])` 删除任务记录、
  分片与合并产物目录；任务不存在返回 `false`，异步合并 PENDING/RUNNING 期间抛 `409`。
  并通过 `POST /upload?action=cancel&identifier=...` 暴露（新增 `AccessControl.ACTION_CANCEL`）。
- **稳定错误语义**：core 失败异常统一实现 `UploadErrorCode.getHttpStatusCode()`——既有
  `ChecksumMismatchException`（`400`）/ `AccessDeniedException`（`401`）/ `QuotaExceededException`（`507`），
  另新增三个带码异常 `UploadValidationException`（`400`）、`UploadTaskNotFoundException`（`404`）、
  `UploadMergeConflictException`（`409`）。新类型分别为 `IllegalArgumentException`、
  `NoSuchElementException`、`IllegalStateException` 的子类，既有宽泛 catch 继续生效；接入方只需
  判一次 `UploadErrorCode` 即可完成状态码映射。
- **Servlet 映射对齐**：`UploadServlet` 依据 `UploadErrorCode` 映射失败状态（`400/401/404/409/507`，
  见文档），并新增 `cancel` action。

### 变更

- `merge`/`submitMerge`/分片校验抛出的异常由裸泛型异常换成上述带码类型——失败场景不变，但 HTTP 语义稳定。
- README / docs/API 补充：confirm 阶段 `finalPath` 契约、`getTask`/`cancelUpload`、清理与孤儿回收语义、
  手工装配（core）对 `upload-file.*` 属性的自理责任、`UploadErrorCode` 状态码表，以及对接既有登录态
  （Bearer/SSO）的 AccessControl 说明。

## [1.0.0-rc.3] - 2026-08-29

**Pre-release。** `1.0.0` GA 前的生产就绪加固。所有新特性默认关闭，从 `rc.2` 升级保持既有行为逐字节不变
（由新增 compat 回归套件保证）。

### 新增

- **访问控制（T6）**：`AccessControl` SPI，内置 `PermitAllAccessControl`（默认，放行）与
  `TokenAccessControl`（常量时间共享令牌比较）；上传/进度/合并/异步合并/下载全部门禁接入校验，
  令牌缺失或错误返回 `401`
- **文件大小与容量配额（T8）**：`max-file-size` 单文件上限（首片与 merge 前双重校验）与可选
  `quota.max-bytes` 全局容量配额（近似估算，超限返回 `507 Insufficient Storage`）
- **清理可观测（T9）**：`CleanupStats` 快照（`getLastStats()`）与统计监听器；接入层每次清理输出一行
  结构化日志（`observability.log-stats`，默认 `true`）
- **任务存储迁移（T10）**：`TaskStoreMigrator` 可在存储间迁移进行中任务（如 `FileTaskStore` →
  `JdbcTaskStore` / `RedisTaskStore`）；显式且幂等，从不自动执行（`migration.enabled` 暴露 Bean）
- **元数据格式版本（T11）**：`UploadTask.schemaVersion`（当前 `1`）；缺字段的旧记录加载时归一化为 `1`
- **多实例清理锁（T7）**：`CleanupLock` SPI，redis 模块提供 `RedisCleanupLock`（`SET NX EX` 租约）；
  未获取租约时跳过本轮（`cleanup.use-redis-lock`）
- 新增配置项：`security.enabled`、`security.token`、`security.header-name`、`max-file-size`、
  `quota.max-bytes`、`cleanup.use-redis-lock`、`observability.log-stats`、`migration.enabled`；
  Servlet 场景提供同名 init-param
- **compat 回归套件（T12）**：以 rc.2 元数据 JSON + 目录布局启动验证默认配置行为；覆盖两处高危组合
  （C1：TTL 静默删任务；C2：内存存储 + 孤儿清理）

### 变更

- `ResumableUploadService` / `ResumableDownloadService` 新增携带令牌的方法重载
  （`uploadChunk(req, token, in)`、`merge(id, token)`、`resolveFile(id, token)` 等）；旧签名委托
  空令牌调用，行为不变
- 启用 `security.enabled` 而未配置令牌时启动即失败（Servlet 上下文与 Spring Boot 均如此），
  避免误配置导致接口静默开放

### 安全

- 全部接口可选共享令牌访问控制（常量时间比较，默认关闭）
- `max-file-size` 上限在文件落盘前即拒绝超限文件
- `quota.max-bytes` 防护多文件场景下的磁盘耗尽

### 修复

- `upload-file-spring-boot-starter` 元数据绑定：`UploadFileProperties` 原先为扁平字段，文档中的点号属性名
  （`merge.fsync`、`cleanup.enabled`、`cleanup.interval`、`jdbc.table-name`、`redis.key-prefix` 等）
  会被 Spring Boot 的 `@ConfigurationProperties` 静默忽略。现已将属性重构为嵌套的
  `merge` / `cleanup` / `async-merge` / `jdbc` / `redis` 配置类，文档化名称按预期生效
  （行为与默认值不变）。

### 兼容性

- 所有新特性默认关闭：`security.enabled`、`quota.max-bytes`、`cleanup.use-redis-lock` 默认关闭，
  `observability.log-stats` 仅在清理实际执行时输出日志
- `UploadTask` 新增 `schemaVersion` 默认按 `1` 处理；rc.2 JSON 依旧可读、回滚安全
- 访问控制为纯新增（默认 `PermitAll`），既有调用方不受影响
- Redis 集成测试在无 Docker 时自动跳过

## [1.0.0-rc.2] - 2026-08-26

**Pre-release。** 第二个候选版本。新增的治理/健壮性特性默认关闭，从 `rc.1` 直接升级即可保持既有行为。

### 新增

- **合并原子化（T2）**：merge 先写同目录临时文件，可选 fsync，再以 `ATOMIC_MOVE` 改名落位；中途失败不再残留坏文件
- **过期任务清理（T1）**：`StorageCleanupService` 删除超过 `cleanup.task-ttl` 未更新的未完成任务（含分片）；`TTL=0` 表示永不清理
- **孤儿数据治理（T3）**：`ChunkStorage.listIdentifiers()` 默认方法；开启后扫描无任务记录的 chunk / merged 目录并清理
- **合并异步化（T4）**：`submitMerge` / `getMergeStatus`，`action=mergeAsync`（HTTP 202）与 `action=mergeStatus`；状态机 `NONE → PENDING → RUNNING → SUCCEEDED/FAILED`；合并进行/完成时拒收新分片
- **可插拔元数据存储（T5）**：新增可选模块 `upload-file-store-jdbc`（`JdbcTaskStore`）与 `upload-file-store-redis`（`RedisTaskStore`）；`upload-file.metadata-store`（`auto|memory|file|jdbc|redis`）
- 新增配置项：`merge.fsync`、`merge.atomic`、`cleanup.enabled`、`cleanup.run-on-startup`、`cleanup.interval`、`cleanup.task-ttl`、`cleanup.orphan-enabled`、`async-merge.enabled`、`async-merge.thread-pool-size`、`metadata-store`、`jdbc.table-name`、`jdbc.init-sql`、`redis.host`、`redis.port`、`redis.password`、`redis.key-prefix`、`redis.ttl-seconds`；Servlet 场景提供同名 init-param

### 变更

- `UploadTask` 新增 `mergeState` / `mergeError` / `mergeStartedAt`；旧 JSON 元数据按 `NONE` 处理（向后兼容）
- merge 失败时删除临时文件；残留临时文件由孤儿清理（T3）回收
- `metadata-store=auto` 完全复刻 rc.1 行为（有 `metadata-dir` → file，否则 memory）
- **merge 先持久化合并状态再删除分片**；若元数据保存失败，分片仍在磁盘上，任务保持可恢复
- 上传服务与清理服务共享 `IdentifierLock`，清理与同一 identifier 的上传/合并互斥（持锁二次校验）
- 后续分片声明的元数据（`chunkTotal` / `chunkSize` / `fileSize` / `fileName`）与首片不一致时返回 `400` 拒绝

### 修复

- `FileTaskStore.list()` 遇到残留 `.meta-*.json` 临时文件或损坏元数据不再抛异常，改为跳过，与 JDBC/Redis 存储行为对齐
- `StorageCleanupService` 在 `stop()` 之后可再次 `start()`
- `submitMerge()` 在 executor 拒收任务时回滚状态为 `NONE`，任务不再卡在 pending 合并
- 内存存储下跳过孤儿清理，避免重启后误删全部磁盘数据
- merge 失败响应不再泄露内部文件路径（细节改为服务端日志记录）

### 安全

- 新增分片大小上限：Spring Boot `max-chunk-size` / Servlet `chunk.max-size`，超限分片在记录前即被拒绝，防磁盘耗尽 DoS
- `MemoryTaskStore` 与其它存储一致校验 identifier（路径穿越的纵深防御）

### 兼容性

- 所有新特性默认关闭（`cleanup.*` 与 `async-merge.enabled` 默认 `false`），裸升级即可保持 rc.1 生产行为
- 既有接口（`merge`、`progress`）与同步 `merge()` 入口不变；现有 `ChunkStorage` 实现无需改动

### 依赖

- 新增可选：`upload-file-store-jdbc`、`upload-file-store-redis`（Jedis 4.4.0）；H2 2.2.224（仅测试）

## [1.0.0-rc.1] - 2026-08-23

**Pre-release。** 初版的首个候选版本，`1.0.0` 正式版发布前 API 仍可能调整。

### 新增

- 大文件分片上传，失败仅重传失败分片
- 断点续传：服务端记录已上传分片，客户端可随时暂停/继续
- 可选的分片 MD5 校验
- 分片合并：按序合并、最终大小校验、自动清理分片
- 基于 HTTP `Range` 的断点续传下载（`206 Partial Content`，不可满足时返回 `416`）
- 任务元数据持久化：内存（`MemoryTaskStore`）或本地 JSON 文件（`FileTaskStore`，重启不丢）
- 可插拔存储 SPI：`TaskStore`（元数据）与 `ChunkStorage`（分片）
- 纯 Servlet 3.0+ 接入：`UploadServlet` / `DownloadServlet`
- Spring Boot 2.x 自动配置：`upload-file-spring-boot-starter`（零配置，`@ConditionalOnMissingBean` 可覆盖）
- 演示用例（含前端页面）：`example/upload-file-demo`
- 中英文双语文档

### 安全

- `identifier` 与 `fileName` 均做路径穿越校验（合并写盘前再次校验）
- 分片与元数据采用「临时文件 + 原子改名」写盘，避免残留半个文件
- `FileTaskStore` / `LocalFileChunkStorage` 内部对 identifier 做防御性重复校验

### 修复

- 合并分片时通过 `fileName` 实现的路径穿越
- 并发首片上传的任务创建竞态（改为按 identifier 的 striped lock）
- MD5 校验不一致时不再丢弃整个上传进度（仅拒收该分片）
- Servlet 3.0 容器下小于 2GB 文件的 `Content-Length` 兼容（`setContentLength` 回退）

### 变更

- 编译目标 `--release 8`，产物为 JDK8 字节码；从源码构建需要 JDK 9+
- 分片与元数据改为原子写盘（临时文件 + 改名）

### 依赖

- Gson 2.10.1、javax.servlet-api 4.0.1（provided）、Spring Boot 2.7.18（provided，仅 starter）
