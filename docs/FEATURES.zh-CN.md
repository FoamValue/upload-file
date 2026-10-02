# 特性与功能列表

> 🇺🇸 [English](FEATURES.md)

本文档是 **`upload-file` `1.0.0`（GA）** 的完整特性与功能清单——面向大文件**分片上传 / 断点续传 /
HTTP `Range` 断点下载**的 Maven 工具包。按能力域组织；[配置参考](#13-配置参考)与
[HTTP API](#10-http-接入) 表格为权威速查。

- [1. 核心上传](#1-核心上传)
- [2. 分片合并](#2-分片合并)
- [3. 下载](#3-下载)
- [4. 任务生命周期与清理](#4-任务生命周期与清理)
- [5. 存储与元数据](#5-存储与元数据)
- [6. 安全](#6-安全)
- [7. 配额与限制](#7-配额与限制)
- [8. 并发与多实例协调](#8-并发与多实例协调)
- [9. 可观测与审计](#9-可观测与审计)
- [10. HTTP 接入](#10-http-接入)
- [11. Spring Boot 集成](#11-spring-boot-集成)
- [12. 扩展点（SPI）](#12-扩展点spi)
- [13. 配置参考](#13-配置参考)
- [14. 模块与演示](#14-模块与演示)
- [15. 兼容性与承诺](#15-兼容性与承诺)

## 1. 核心上传

| 特性 | 说明 |
| --- | --- |
| 分片上传 | 大文件拆分为多个分片依次上传，失败只重传失败分片（`uploadChunk(request, token, in)`） |
| 断点续传 | 服务端记录「已上传分片」，客户端可随时暂停/继续 |
| 分片完整性 | 落盘时可选校验每个分片 MD5；`verify-checksum=true`（默认）将实际 MD5 与声明 `chunkMd5` 比对 |
| 校验不可跳过 | `require-checksum=true` 时，开启校验后缺失 `chunkMd5` 的分片将被拒绝（M1） |
| 带字节上限的流式写入 | 分片字节经有界流写入，超过 `max-chunk-size` 立即中止，不会先落盘 |
| 分片复用 | `isChunkUploaded(identifier, chunkIndex)` 按索引报告状态，客户端跳过已上传分片 |
| 重复分片幂等 | 对已存在的分片索引重复上传不会破坏已存数据 |
| 上传进度 | `getProgress(identifier)` 返回当前 `UploadProgress`（已上传分片索引、总片数等） |
| 跨分片元数据一致性 | 与首片声明不一致的后续分片将被拒绝 |
| 声明片数上限 | `chunkTotal` 设上限（`MAX_CHUNK_TOTAL = 100 000`），防御 `O(chunkTotal)` CPU 消耗 |
| 受信读 API | `getProgressTrusted` / `getTaskTrusted` / `getMergeStatusTrusted` 供服务端内部调用，跳过访问控制 |

## 2. 分片合并

| 特性 | 说明 |
| --- | --- |
| 顺序合并 | `merge(identifier)` 按索引顺序将分片合入 `mergedFileDir/<identifier>/<fileName>` |
| 缺片保护 | 合并且所有分片必须在盘，否则抛 `409` `UploadMergeConflictException` |
| 最终大小校验 | 合并文件大小与声明 `fileSize` 比对，不一致则丢弃产物并抛错 |
| 合并原子化 | 先写同目录临时文件，可选 `fsync`（`merge.fsync`），再原子改名落位；中途失败不留坏文件 |
| 崩溃安全顺序 | 合并状态在**删除分片之前**持久化，中途保存失败不会出现「分片已删、任务未合并」 |
| 合并异步化 | `submitMerge(identifier)` 返回 `202` 风格 `MergeStatus`（`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`）；进行/完成时拒收新分片 |
| 状态轮询 | `getMergeStatus(identifier)` 轮询异步合并状态 |
| 幂等提交 | `PENDING`/`RUNNING` 期间重复提交返回当前状态，不重复执行 |
| 合并结果 | `UploadResult` 携带合并状态、最终路径与大小 |
| 合并后清理 | 合并成功后删除分片；残留分片由孤儿数据 GC 回收 |

## 3. 下载

| 特性 | 说明 |
| --- | --- |
| 完整下载 | `GET /download?identifier=xxx` 以 `200` 提供合并文件 |
| Range 下载 | 支持 HTTP `Range`，单段/多段区间返回 `206 Partial Content` |
| 不可满足区间 | 无法满足的区间返回 `416` 与文档化错误体 |
| Range 解析 | `DownloadRange.parse(String)` 解析单段/多段 `Range` 头并识别不可满足区间，可在 HTTP 层之外复用 |
| 文件解析 | `resolveFile(identifier)` / `resolveFileName(identifier)` 解析合并产物（受访问控制） |
| 区间写出 | `writeRange(File, start, length, out)` 流式写出文件任意字节窗口 |

## 4. 任务生命周期与清理

| 特性 | 说明 |
| --- | --- |
| 任务记录 | 每次上传为 `TaskStore` 中的一条 `UploadTask`（identifier、文件元数据、分片列表、合并状态、时间戳） |
| 进度查询 | `getProgress(identifier)` / HTTP `action=progress` |
| 显式读取 | `getTask(identifier)` 返回原始任务记录（集成侧「confirm 入库」阶段的稳定读接口） |
| 显式取消 | `cancelUpload(identifier)` / HTTP `action=cancel` 立即回收任务分片与合并产物，无需等待清理调度 |
| 幂等取消 | `http.cancel-not-found-status=200` 使取消不存在任务幂等（默认 `404`） |
| 过期任务清理 | `StorageCleanupService` 按 `cleanup.task-ttl` 定时清理未完成任务（`cleanup.enabled`、`cleanup.interval`） |
| 启动即清理 | `cleanup.run-on-startup=true` 在启动时执行一次清理 |
| 孤儿数据 GC | `cleanup.orphan-enabled=true` 对比 `TaskStore` 与磁盘，清理元数据已丢失的分片/文件 |
| 逐条隔离 | 每条记录独立 try-catch 处理，单条脏数据不会中断整个清理过程 |
| 清理统计 | 每次清理输出结构化统计（`CleanupStats`：上次运行、清理任务数、清理孤儿数、耗时、错误），并保留可查询快照（`getLastStats`） |
| 清理钩子 | `setErrorListener` / `setStatsListener` 向集成方暴露清理事件 |
| Redis 清理租约 | `cleanup.use-redis-lock=true` 保证集群中同一时刻只有一个实例执行清理调度 |

## 5. 存储与元数据

| 特性 | 说明 |
| --- | --- |
| 可插拔 `TaskStore` SPI | `get` / `save` / `remove` 契约；可自研后端或使用内置实现 |
| `FileTaskStore` | 元数据 JSON 落盘（`metadata-dir`），重启不丢 |
| `MemoryTaskStore` | 内存元数据；未配置 `metadata-dir` 时的默认（`metadata-store=memory`） |
| `JdbcTaskStore` | JDBC 元数据，自动建表（`upload-file-store-jdbc`；`metadata-store=jdbc`） |
| `RedisTaskStore` | Redis 元数据，支持键前缀与记录 TTL（`upload-file-store-redis`；`metadata-store=redis`） |
| `auto` 存储选择 | `metadata-store=auto` 保持旧行为：配置 `metadata-dir` 用文件存储，否则内存 |
| 元数据版本化 | `schemaVersion` 字段保障格式安全演进；迁移器拒绝不支持的版本 |
| 任务存储迁移 | `TaskStoreMigrator` 在存储间迁移进行中任务（如 `FileTaskStore` → JDBC/Redis）；从不自动运行，经 `migration.enabled` 暴露为 Bean |
| 可插拔 `ChunkStorage` SPI | 分片可落本地盘（默认）或接 OSS/HDFS 等对象存储 |
| 默认 `LocalFileChunkStorage` | 「临时文件 + 原子改名」分片写入 |

## 6. 安全

| 特性 | 说明 |
| --- | --- |
| 共享令牌访问控制 | 可选令牌校验作用于全部入口（`security.enabled` + `security.token`）；令牌可经可配置请求头（`security.header-name`）或 `token` 查询参数传递；常量时间比较 |
| 可区分的访问决策 | `AccessControl.decide(...)` 返回 `AccessDecision`，拒绝可携带 `401`（未认证）或 `403`（越权） |
| 旧接口桥接 | 旧 `check(...)` 保留为 `@Deprecated` 默认方法，经 `decide()` 桥接，既有实现零改动 |
| 路径穿越防护 | `identifier` 与 `fileName` 均校验（安全字符），每个存储实现内部均校验 |
| 下载路径校验 | 下载解析经规范路径前缀校验（`getCanonicalFile`），合并文件不可能被解析到 `mergedFileDir` 之外 |
| 大小限制 | `max-chunk-size`、`max-request-size`、`max-file-size` 在落盘前/落盘时拒绝超限分片与文件 |
| 按实测字节计数 | 配额与大小限制统计**实际接收字节**，绝不信任客户端声明的 `fileSize`/`chunkSize` |
| 无界默认 fail-fast | `max-chunk-size`、`max-file-size`、`quota.max-bytes` 至少配置一个；三者全无界时启动失败 |
| 校验不可跳过 | `verify-checksum` + `require-checksum` 时，缺失 `chunkMd5` 的分片被拒绝（M1） |
| 日志注入过滤 | 写日志前过滤 CR/LF/控制字符 |
| 合并冲突防护 | `merge()`/`writeMergedFile()` 拒绝记录 `chunkTotal` 超限的任务，即使元数据被直接写入存储 |
| 配额执行 | 超出全局容量配额返回 `507 Insufficient Storage`；单文件超限返回 `400` |

## 7. 配额与限制

| 特性 | 说明 |
| --- | --- |
| 全局容量配额 | `quota.max-bytes` 限制所有任务累计占用（超限 `507`） |
| 单文件大小上限 | `max-file-size` 限制单个文件总大小（超限 `400`） |
| 分片大小上限 | `max-chunk-size` 限制单个分片；multipart 层与服务双重限制；`-1` 关闭 |
| 可插拔 `QuotaStore` SPI | 默认 `TaskStoreQuotaStore` 由 `TaskStore` 重算用量；`quota.store=redis` 切换到原子 `RedisQuotaStore` |
| 自动对账 | `RedisQuotaStore.reconcile` 启动期执行；合并未确认任务的配额在清理时回收 |
| 不信任声明值 | 所有限制按实际接收字节数执行，声明 `0`/负值无法绕过 |

## 8. 并发与多实例协调

| 特性 | 说明 |
| --- | --- |
| 按 identifier 锁 | 上传、合并与清理共享同一把 identifier 锁，后台 GC 不与实时数据竞争 |
| 锁提供方 | `lock.identifier-lock`：`local`（进程内）、`redis`（分布式）；另提供条带化进程内实现 |
| 租约 TTL | 分布式 identifier 锁带 TTL（`lock.ttl`），持锁者崩溃自动过期 |
| 续租 | 持有期间自动续租（`lock.renew-interval`；`0` 取 `ttl/3`），临界区超过 TTL（如慢盘大合并）仍保持互斥 |
| 获取超时 | `lock.acquire-timeout` 限制等待分布式锁的时间 |
| Redis 清理租约 | `cleanup.use-redis-lock=true` 时集群中仅一个实例执行清理调度 |
| 异步合并线程池 | `async-merge.enabled` + `async-merge.thread-pool-size` 在有界后台池中执行合并 |

## 9. 可观测与审计

| 特性 | 说明 |
| --- | --- |
| 清理统计日志 | `observability.log-stats=true`（默认）每次清理输出一行结构化统计 |
| 清理快照 | 经 `StorageCleanupService.getLastStats()` 查询 `CleanupStats` 快照 |
| 访问日志 | `observability.access-log=true` 时每个入口检查输出一行结构化访问决策 |
| 访问日志范围 | `observability.access-log-scope`：`task`（默认：拒绝 + 任务级放行，跳过逐分片上传噪音）· `deny`（仅拒绝）· `all`（每条决策） |
| 审计钩子 | `AccessControlListener` 在每个入口触发（放行/拒绝 + 决策耗时）；MVC 与 Servlet 路径共用同一套监听器接线 |
| 审计上下文 | `AccessContext` / `AccessContextHolder` 在决策路径中透传 method/URI/IP/UA；监听器 6 参重载接收它们（5 参旧版保持源码兼容） |
| 日志卫生 | 访问日志始终写入净化后的 IP/UA 值 |

## 10. HTTP 接入

| 特性 | 说明 |
| --- | --- |
| 上传端点 | `POST /upload`（multipart，文件字段 `file`），参数 `identifier`、`fileName`、`fileSize`、`chunkSize`、`chunkTotal`、`chunkIndex`、`chunkMd5`；返回进度 JSON |
| 进度查询 | `GET /upload?action=progress&identifier=xxx` |
| 合并 | `POST /upload?action=merge&identifier=xxx` |
| 异步合并 | `POST /upload?action=mergeAsync&identifier=xxx`（`202`）；`GET /upload?action=mergeStatus&identifier=xxx` |
| 取消 | `POST /upload?action=cancel&identifier=xxx` —— 回收分片与合并产物 |
| 下载端点 | `GET /download?identifier=xxx`（`200`）；带 `Range` 头（`206` / `416`） |
| 端点开关 | `endpoint.enabled=false` 为纯 bean 模式；`endpoint.upload-enabled` / `endpoint.download-enabled` 分别开关；`/download` **默认不注册**（最小暴露面） |
| Bean 可覆盖 | Servlet 实例与其注册均可被宿主同名/同类型 Bean 覆盖 |
| 稳定错误映射 | `UploadErrorCode` → 稳定 HTTP 状态：`400` 参数非法/超限/MD5 不匹配 · `401` 访问被拒 · `403` 越权 · `404` 不存在 · `409` 合并冲突 · `416` Range 不可满足 · `507` 配额超限 |
| 符号错误码 | 每个带码异常携带稳定符号（`UPLOAD_VALIDATION`、`UPLOAD_CHECKSUM`、`UPLOAD_NOT_FOUND`、`UPLOAD_MERGE_CONFLICT`、`ACCESS_DENIED`、`QUOTA_EXCEEDED`、`MISSING_ACTION`、`UPLOAD_UNKNOWN_ACTION`、`MISSING_IDENTIFIER`、`UPLOAD_SERVER_ERROR`、`RANGE_NOT_SATISFIABLE`） |
| 错误体模式 | `http.error-body=legacy`（默认，端点模型）或 `standard`（统一 `UploadHttpError` 体） |
| 错误渲染 SPI | `UploadErrorRenderer` 允许宿主提供自有失败体（starter 会消费） |
| multipart 策略 | `multipart.strategy`：`component`（限制写入 `@MultipartConfig`）· `spring`（跟随 `spring.servlet.multipart.*`）· `unlimited`（无容器上限；服务层仍执行自身限制） |

## 11. Spring Boot 集成

| 特性 | 说明 |
| --- | --- |
| 零配置自动装配 | `upload-file-spring-boot-starter`（Boot 2.x，`javax`）与 `upload-file-spring-boot-starter-jakarta`（Boot 3/4，`jakarta`）由 `upload-file.*` 属性装配全套 |
| 服务 Bean | `ResumableUploadService`、`ResumableDownloadService`、`StorageCleanupService` 自动装配，可直接注入 |
| 受信读门面 | `TrustedUploadService` 默认暴露供服务端读取；`trusted-upload-service.enabled=false` 关闭或覆写 Bean |
| 端点注册 | 上传/下载 Servlet 以 `ServletRegistrationBean` 注册，可被宿主 Bean 替换 |
| 装配护栏 | 安全默认与装配冲突在启动期显式暴露 |
| BOM | `upload-file-bom` 统一 7 个库模块版本；宿主 `import` 后只写 `artifactId` |
| Demo profile | Boot 4 demo 支持 `jdbc`（内嵌 H2）与 `redis` 可选元数据存储 profile |

## 12. 扩展点（SPI）

| SPI | 契约 | 内置实现 |
| --- | --- | --- |
| `TaskStore` | 任务记录的 `get` / `save` / `remove` | `FileTaskStore`、`MemoryTaskStore`、`JdbcTaskStore`、`RedisTaskStore` |
| `ChunkStorage` | 分片保存/存在性/删除（4 参带字节上限 `default` 重载） | `LocalFileChunkStorage` |
| `QuotaStore` | 配额占用/释放 + `reconcile`（默认空操作） | `TaskStoreQuotaStore`、`RedisQuotaStore` |
| `AccessControl` | `decide(...) → AccessDecision`（旧 `check(...)` 默认桥接） | `PermitAllAccessControl`、`TokenAccessControl`、`AbstractAccessControl` |
| `AccessControlListener` | 放行/拒绝事件 + 决策耗时（6 参重载，5 参旧版） | starter/servlet 装配访问日志监听器 |
| `IdentifierLockProvider` | `lock(identifier) → IdentifierLockHandle` | `IdentifierLock`、`StripedIdentifierLockProvider`、`RedisIdentifierLockProvider` |
| `UploadErrorRenderer` | 失败体渲染 | `UploadErrorRenderers` |

## 13. 配置参考

前缀 `upload-file`。所示值为默认值。

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `storage-dir` | `./upload-file-data` | 分片与合并文件根目录 |
| `metadata-dir` | *(空)* | 任务元数据持久化目录；空 = 内存存储（`MemoryTaskStore`） |
| `verify-checksum` | `true` | 校验每个分片 MD5 |
| `require-checksum` | `false` | 与 `verify-checksum` 搭配，缺失 `chunkMd5` 的分片被拒绝 |
| `upload-url` | `/upload` | 上传 Servlet 映射 |
| `download-url` | `/download` | 下载 Servlet 映射 |
| `max-chunk-size` | `10 MB` | 单个分片最大字节数；`-1` 关闭分片上限 |
| `max-request-size` | `-1` | multipart 请求最大字节数；`-1` 不限制 |
| `max-file-size` | `-1` | 单个文件总大小上限；`-1` 不限制 |
| `metadata-store` | `auto` | `auto` · `memory` · `file` · `jdbc` · `redis` |
| `merge.fsync` | `true` | rename 前 fsync 合并临时文件 |
| `merge.atomic` | `true` | 「临时文件 + 原子改名」合并 |
| `cleanup.enabled` | `false` | 启动过期任务/孤儿清理调度 |
| `cleanup.run-on-startup` | `false` | 启动时执行一次清理 |
| `cleanup.interval` | `1h` | 清理周期 |
| `cleanup.task-ttl` | `24h` | 未完成任务过期时间；`0`/负值 = 永不清理 |
| `cleanup.orphan-enabled` | `false` | 启用孤儿数据 GC |
| `cleanup.use-redis-lock` | `false` | 集群中仅一个实例执行清理 |
| `async-merge.enabled` | `false` | 启用异步合并 |
| `async-merge.thread-pool-size` | `2` | 异步合并线程数 |
| `endpoint.enabled` | `true` | 总开关；`false` = 纯 bean 模式 |
| `endpoint.upload-enabled` | `true` | 注册上传 Servlet |
| `endpoint.download-enabled` | `false` | 注册下载 Servlet（默认关闭） |
| `http.error-body` | `legacy` | `legacy` 端点模型或 `standard` 统一错误体 |
| `http.cancel-not-found-status` | `404` | 取消不存在任务的状态码；`200` = 幂等 |
| `multipart.strategy` | `component` | `component` · `spring` · `unlimited` |
| `security.enabled` | `false` | 访问控制总开关 |
| `security.token` | *(空)* | 共享访问令牌 |
| `security.header-name` | `X-Access-Token` | 令牌请求头名（`token` 查询参数同样接受） |
| `quota.max-bytes` | `0` | 全局容量配额；`0`/负值 = 关闭 |
| `quota.store` | `task-store` | `task-store`（由 TaskStore 重算）或 `redis`（原子） |
| `observability.log-stats` | `true` | 每次清理输出结构化统计 |
| `observability.access-log` | `false` | 输出结构化访问决策 |
| `observability.access-log-scope` | `task` | `task` · `deny` · `all` |
| `migration.enabled` | `false` | 暴露 `TaskStoreMigrator` Bean（从不自动运行） |
| `lock.identifier-lock` | `local` | `local`（进程内）或 `redis`（分布式） |
| `lock.acquire-timeout` | `10s` | 等待分布式 identifier 锁的最大时长 |
| `lock.ttl` | `30s` | 分布式锁租约 TTL |
| `lock.renew-interval` | `0`（`ttl/3`） | 持锁期间的续租间隔 |
| `jdbc.table-name` | `upload_task` | JDBC 表名 |
| `jdbc.init-sql` | *(自动建表 DDL)* | 建表 SQL（`%s` 替换为表名） |
| `redis.host` | `localhost` | Redis 主机 |
| `redis.port` | `6379` | Redis 端口 |
| `redis.password` | *(空)* | Redis 密码（空 = 无认证） |
| `redis.key-prefix` | `upload:task:` | Redis 键前缀 |
| `redis.ttl-seconds` | `0` | Redis 记录 TTL；`0` = 不过期 |

## 14. 模块与演示

| 模块 | 用途 |
| --- | --- |
| `upload-file-core` | 核心纯 Java 组件：模型、校验、存储 SPI、上传/下载/清理服务 |
| `upload-file-servlet` | Servlet 3.0+（`javax.servlet`）接入：上传 Servlet、下载 Servlet |
| `upload-file-servlet-jakarta` | Jakarta Servlet 5/6 孪生版（FQCN 完全相同） |
| `upload-file-spring-boot-starter` | Spring Boot 2.x 自动配置（`javax`） |
| `upload-file-spring-boot-starter-jakarta` | Spring Boot 3/4 自动配置（`jakarta`） |
| `upload-file-store-jdbc` | 可选 JDBC `TaskStore`（自动建表） |
| `upload-file-store-redis` | 可选 Redis `TaskStore`（基于 Jedis） |
| `upload-file-bom` | 统一各库模块版本 |
| `example/upload-file-demo` | Spring Boot 2 演示（分片上传 UI） |
| `example/upload-file-boot4-demo` | Spring Boot 4 演示，含 `jdbc`/`redis` 元数据存储 profile |
| `example/upload-file-servlet-demo` | 纯 Servlet 演示（web.xml 装配，Jetty） |

## 15. 兼容性与承诺

| 项 | 承诺 |
| --- | --- |
| 运行环境 | JDK 8+ 字节码（`--release 8`）；JDK 8 可直接引用产物 |
| 依赖 | 仅 Gson（核心模块） |
| Servlet 双线 | `javax`（Servlet 3/4，Boot 2）与 `jakarta`（Servlet 5/6，Boot 3/4）孪生版，FQCN 与 `upload-file.*` 属性一致 |
| 版本策略 | 自 `1.0.0` 起语义化版本；破坏性变更只能进入 `2.0.0` |
| GA 维护线 | `1.0.0.x` 提供安全修复；javax 线自 GA 起为维护态，`2.0.0` 收敛到 jakarta 单线 |
| 二进制兼容 | CI 以 Revapi 门禁强制保障（每次发布前验证） |

> 另见：[架构设计](DESIGN.zh-CN.md) · [HTTP API 参考](API.zh-CN.md) · [未来优化方向](ROADMAP.zh-CN.md) · [更新日志](../CHANGELOG.zh-CN.md)
