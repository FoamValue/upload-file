# 更新日志

本文件记录项目的所有重要变更。

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 🇺🇸 [English](CHANGELOG.md)

## [Unreleased]

- 计划：Spring Boot 3.x（`jakarta.servlet`）适配。

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
