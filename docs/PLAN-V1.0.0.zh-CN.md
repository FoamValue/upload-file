# V1.0.0 范围声明（SOW）与 API 冻结

> 🇺🇸 [English](PLAN-V1.0.0.md)
>
> 本文件是 `1.0.0` GA 的范围声明（Statement of Work）。rc.8 合并后，**公开 API 与
> `upload-file.*` 属性面即冻结**；`1.0.0` 仅做版本号提升与发布公告，不再改代码。自 `1.0.0`
> 起遵循[语义化版本](https://semver.org/lang/zh-CN/)：破坏性变更只能进入 `2.0.0`。
> `1.0.0-rc.9` 为冻结期内的**安全收口**：仅 additive 变更（新增 `require-checksum` 属性）与
> 默认值收紧（`max-chunk-size` 默认 10 MB、三层上限全无界时启动失败），不破坏任何冻结契约
> （见 §3）。

## 一、GA 范围

以 rc.8 冻结的能力面为准。GA 提供：

- **分片上传 / 断点续传**：`POST /upload`（chunk）、`action=merge`、`action=mergeAsync`、
  `action=mergeStatus`、`action=cancel`、`action=progress`；
- **断点下载**：`GET /download`（`Range` → 206/416）；
- **元数据存储**：内存 / 文件 / JDBC / Redis（`upload-file.metadata-store`）；
- **多实例能力**：分布式 identifier 锁、原子配额、清理租约、Redis 索引治理；
- **安全与合规**：`AccessControl`（`401`/`403` 可区分）、`AccessControlListener` + 请求上下文
  `AccessContext`、统一错误体（`legacy`/`standard`/宿主 Bean）；
- **可观测**：`observability.log-stats`、`observability.access-log` + `access-log-scope`；
- **两条 servlet 线**：`javax`（Servlet 4 / Boot 2.7）与 `jakarta`（Servlet 6 / Boot 4）。

## 二、冻结的公开 API

### 核心 SPI / 门面（`upload-file-core`）

| 类型 | 说明 | 冻结状态 |
| --- | --- | --- |
| `TaskStore` | 任务元数据存储 SPI（`get/save/remove/list`） | 冻结 |
| `ChunkStorage` | 分片存储 SPI | 冻结 |
| `QuotaStore` | 全局容量配额 SPI（含 `reconcile(TaskStore)` 默认方法） | 冻结 |
| `IdentifierLockProvider` / `IdentifierLockHandle` | 单 identifier 串行化 SPI | 冻结 |
| `CleanupLock` | 清理租约 SPI | 冻结 |
| `AccessControl` | 访问决策 SPI（`decide(...)` 新入口，`check(...)` `@Deprecated`） | 冻结 |
| `AccessControlListener` | 访问决策监听（5 参 + 6 参 `default` 重载） | 冻结 |
| `AccessContext` / `AccessContextHolder` | 审计请求上下文（rc.8 新增） | 冻结 |
| `UploadErrorRenderer` | 失败体渲染 SPI | 冻结 |
| `ResumableUploadService` / `ResumableDownloadService` | 核心服务 | 冻结 |
| `TrustedUploadService` | 受信（无门控）只读门面 | 冻结 |
| `StorageCleanupService` | 过期任务 / 孤儿数据清理 | 冻结 |
| `TaskStoreMigrator` | 元数据迁移工具 | 冻结 |

### 存储实现（`upload-file-store-jdbc` / `upload-file-store-redis`）

`JdbcTaskStore`、`RedisTaskStore`、`RedisQuotaStore`、`RedisIdentifierLockProvider`、
`RedisCleanupLock` 的公开构造器与静态工厂冻结。

### HTTP 层（`upload-file-servlet` / `upload-file-servlet-jakarta`）

`UploadServlet` / `DownloadServlet` 的端点契约、失败体形态与可覆写 Bean 契约
（`uploadFileServlet`、`uploadFileServletRegistration` 等）冻结。

## 三、冻结的配置属性（`upload-file.*`）

`storage-dir`、`metadata-dir`、`metadata-store`、`verify-checksum`、`upload-url`、`download-url`、
`max-chunk-size`、`max-request-size`、`max-file-size`、`merge.*`、`cleanup.*`、`async-merge.*`、
`endpoint.*`、`http.*`、`multipart.*`、`security.*`、`quota.*`、`observability.*`、`migration.*`、
`jdbc.*`、`redis.*`、`lock.*`（含 rc.8 `lock.renew-interval`）。

rc.8 新增/变更：

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `lock.renew-interval` | `ttl/3` | 分布式锁续租周期；`0` 表示按 `ttl/3` 推导 |
| `observability.access-log-scope` | `task` | `task`/`deny`/`all`；`all` 恢复 rc.7 逐决策日志（**默认行为变更，仅日志量**） |
| `quota.store=redis` | 启动自动对账 | 修正 Redis 数据丢失（低估）与残留预留（高估） |
| `trusted-upload-service.enabled` | `true` | 是否暴露 `TrustedUploadService` Bean |

rc.9 新增/变更（安全收口，additive，纳入冻结面）：

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `require-checksum` | `false` | `verify-checksum + require-checksum` 时缺失 `chunkMd5` 的分块被拒绝并删除（新增属性） |
| `max-chunk-size` | `10 MB` | 默认由 `-1`（不限）收紧为 10 MB（breaking-default，仅默认值；显式配置不受影响） |
| request/chunk/file 三层上限 | 全部无界时启动失败 | `max-request-size` 未配置时按 `max-chunk-size`/`max-file-size` 推导（+1 MB）；全无界则 fail-fast（新增行为） |

## 四、`@Deprecated` 项保留策略

以下成员自 `1.0.0` 起保留、**不删除**，计划 `2.0.0` 移除：

- `ResumableUploadService.getTask(String)`（无门控）→ 用 `getTask(String, String)` 或
  `TrustedUploadService.getTask(String)`；
- `ResumableUploadService.isChunkUploaded(String, int)`（无门控）→ 用带 token 重载或
  `TrustedUploadService`；
- `AccessControl.check(...)` → 覆写 `decide(...)`。

## 五、javax 线降级策略

- `upload-file-servlet` 与 `upload-file-spring-boot-starter`（javax / Boot 2.7）自 GA 起标注
  **maintenance / deprecated**：仅接受安全修复，不再新增特性。
- `2.0.0` 收敛到 **jakarta 单线**（`upload-file-servlet-jakarta` /
  `upload-file-spring-boot-starter-jakarta`）。
- 迁移路径：升级到 Boot 3.x/4.x 后替换坐标为 `-jakarta` 版本；包名不变，仅 `javax.*` → `jakarta.*`
  由容器/框架负责。

## 六、升级 / 回滚矩阵（rc.7 → rc.8 → 1.0.0）

| 行为 | rc.7 | rc.8 / 1.0.0 | 回滚 |
| --- | --- | --- | --- |
| `quota.store=redis` 计数 | 可能漂移 / 泄漏 | 启动自动对账 + 清理回收 | 设 `quota.store=task-store`（无状态、无漂移） |
| 分布式 identifier 锁 | 固定 TTL、不续租 | watchdog 续租（`ttl/3`） | 设 `lock.renew-interval` 或 `lock.identifier-lock=local` |
| `RedisTaskStore` 索引迁移 | 非原子 | Lua 原子迁移 | 无（幂等，无需回滚） |
| `RedisTaskStore.list()` | 单次 `MGET` | 分批 `MGET` | 无（结果等价） |
| 审计监听 | 5 参（无上下文） | 新增 6 参 `default` 重载 | 旧 5 参实现零改动 |
| `access-log` 输出 | 每决策一行 | 默认 `task` 级 | 设 `access-log-scope=all` 恢复 rc.7 |
| `TrustedUploadService` | 宿主自建 | starter 自动装配 | 设 `trusted-upload-service.enabled=false` 或覆写 Bean |

**回滚步骤**：`1.0.0 → rc.8` 无配置变化；`rc.8 → rc.7` 时按上表逐项恢复（`access-log-scope=all`、
`quota.store=task-store`、`lock.identifier-lock=local` 可选）。

## 七、兼容承诺

- **无 breaking API**：rc.8 对 rc.7 的变更均为 additive（新增 SPI 默认方法 / 新方法 / 新属性 / 新产物）；
  唯一受控默认变更为 `access-log-scope` 的日志量，可用 `all` 恢复。
- **无磁盘布局 / 任务元数据格式变化**：chunk 目录、merged 目录与 `UploadTask` JSON 结构不变。
- **无 Redis 索引结构变化**：索引仍为 `ZSET`，仅迁移过程原子化。
- **二进制兼容门禁**：CI 以 rc.7 为基线运行 revapi，任何非 additive 变更使构建失败
  （见根 `pom.xml` 的 `compat-check` profile）。

## 八、GA 准入判据

`1.0.0` 可发布当且仅当：

1. rc.7 反馈的 P1-1/P1-2/P1-3 全部闭环（T44/T45）；
2. rc.6 反馈顺延的 P2-1/P2-2 闭环（T47/T48）；
3. 本 SOW 评审通过，API 与属性面冻结；
4. 二进制兼容门禁对 rc.7 基线全绿，`upload-file-bom` 发布可用；
5. JDK 17+ 全 reactor `mvn verify` 全绿。
