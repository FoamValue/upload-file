# V1.0.0-rc.3 任务开发计划（生产就绪加固）

> 🇺🇸 [English](PLAN-V1.0.0-rc.3.md)
>
> 对应 1.0.0 正式版发布前的最后加固版本。rc.3 不新增对外能力卖点，聚焦「生产可支撑性」：
> 关闭 rc.1 → rc.2 分析（[ANALYSIS](ANALYSIS-V1.0.0-rc.2.zh-CN.md)）遗留的**鉴权 / 多实例 / 配额 / 可观测 / 迁移**五类缺口，
> 并为 1.0.0 GA 固化范围与 API 契约。
>
> ⏳ **状态：计划中，尚未实现。**

## 一、目标与范围

rc.2 已完成 P0（T1–T5），功能链路自洽、测试通过，但作为「1.0.0 正式生产版本」存在以下功能缺口
（来源：产品评审 + [ANALYSIS](ANALYSIS-V1.0.0-rc.2.zh-CN.md) 未闭环项）：

| 编号 | 缺口 | 说明 | rc.3 任务 |
| --- | --- | --- | --- |
| G1 | 无鉴权/访问控制 | 上传/下载端点完全开放，`identifier` 即主键 | T6 |
| G2 | 多实例/HA 名不副实 | 宣称多节点但无调度锁、chunk 本地盘不共享 | T7 |
| G3 | 无大小上限/配额 | 仅 `max-chunk-size`，无总文件大小与容量配额 | T8 |
| G4 | 无可观测性 | 清理仅日志，无统计/metrics | T9 |
| G5 | 无迁移工具 | FileTaskStore → JDBC/Redis 切换即丢进行中任务 | T10 |
| G6 | 无元数据版本 | 元数据格式演进无 `schemaVersion` | T11 |
| G7 | 无 compat 回归 | rc.1/rc.2 数据+默认配置回归、C1/C2 高危用例缺失 | T12 |
| G8 | 无 1.0.0 范围定义 | 正式版 SOW 缺失，API 冻结无声明 | T13 |

## 二、架构与约束

沿用现有模块化分层，保持 core 无框架依赖（JDK 8）、新能力默认关闭：

```
upload-file (父 POM / 聚合器)
├── upload-file-core                        # 纯 Java，实现 T6~T11 核心逻辑
├── upload-file-servlet                     # Servlet 接入，新增 init-param 装配
├── upload-file-spring-boot-starter         # 自动配置，新增 upload-file.* 配置项
├── upload-file-store-jdbc   [可选]         # 迁移工具目标端之一
├── upload-file-store-redis  [可选]         # 迁移工具目标端之一 / 调度锁后端
└── example/…                               # 演示用例同步更新
```

约束：
- 所有业务逻辑进 core，servlet / starter 只做装配与配置映射；
- 新组件遵循 `@ConditionalOnMissingBean` / init-param 覆盖模式；
- **所有新能力默认关闭或保持旧行为**，升级 rc.2 → rc.3 零行为变更；
- rc.3 是 1.0.0 前最后一个 rc，**之后冻结 API**：rc.3 中的 SPI 变更须评估破坏性并记录。

## 三、任务拆解

### T6 可选访问控制（G1）

- **涉及文件**：core 新增 `AccessControl` SPI（`check(identifier, action)`）；内置 `PermitAllAccessControl` 与
  `TokenAccessControl`（校验请求头/查询参数中的共享令牌）；`ResumableUploadService` / `ResumableDownloadService`
  在入口调用；`UploadServlet` / `DownloadServlet` 解析令牌。
- **方案**：仅当 `security.token` 非空时才启用校验，`PermitAll` 为默认。令牌经 `MessageDigest.isEqual`
  常量时间比较，避免时序攻击。
- **配置**：`upload-file.security.enabled`、`upload-file.security.token`、`upload-file.security.header-name`
  （见第四节）。
- **验收**：未配置令牌时行为与 rc.2 逐字节一致；配置后 upload/progress/merge/download 均返回 `401`（缺失或错误）；
  header 与查询参数两种传递方式可用；单元测试覆盖 401 / 200 / 常量时间比较。
- **预估**：1 人天。

### T7 多实例部署约束与可选调度锁（G2）

- **涉及文件**：`StorageCleanupService`（可选 Redis 调度锁）、docs 部署约束文档、`README`。
- **方案**：
  - **文档化（必做）**：明确「单实例 / 共享盘」为默认支持形态；`metadata-store=jdbc|redis` 时，若 chunk 仍为
    本地盘，则多实例仅限共享盘场景，并写入「横向扩容需对象存储」说明。
  - **可选调度锁（默认关闭）**：`cleanup.use-redis-lock=true` 时，cleanup 周期任务通过 Redis `SET NX EX`
    获取租约，避免多实例重复清理/重复 GC。仅依赖已存在的 `upload-file-store-redis` 模块。
- **配置**：`upload-file.cleanup.use-redis-lock`（默认 `false`）。
- **验收**：默认关闭无调度行为变化；开启后两实例同时启动仅一个执行清理；锁异常时跳过本轮并告警，不阻塞业务。
- **预估**：1.5 人天。

### T8 文件大小上限与容量配额（G3）

- **涉及文件**：`ResumableUploadService`（merge 前与分片落盘前校验）、`ChunkUploadRequest` 校验、starter/servlet 配置。
- **方案**：
  - **单文件总大小上限**：`max-file-size`，在首个分片登记 `fileSize` 时与 merge 前双重校验，超限拒绝 `400`；
  - **可选全局容量配额**（默认关闭）：`quota.max-bytes`，基于 `TaskStore` 汇总已合并文件大小 + 当前任务声明
    `fileSize` 近似估算；上传分片与 merge 前校验，超限拒绝 `507 Insufficient Storage`。
- **配置**：`upload-file.max-file-size`（默认 `-1`）、`upload-file.quota.max-bytes`（默认 `-1`）。
- **验收**：超限文件在首片即被拒绝、不落盘；merge 时若累计超配额被拒绝；`-1` 时行为与 rc.2 一致。
- **预估**：1 人天。

### T9 可观测性最小集（G4）

- **涉及文件**：`StorageCleanupService` 新增 `CleanupStats`（上次执行时间、清理任务/分片/孤儿条数、耗时、错误）；
  `UploadServlet` / `DownloadServlet` 可选请求计数；starter 输出结构化日志。
- **方案**：`CleanupStats` 作为 Bean 暴露可查询；每次清理结束写入一行结构化日志
  `upload-file cleanup: {run, cleanedTasks, cleanedOrphans, elapsedMs, error}`。
- **配置**：`upload-file.observability.log-stats`（默认 `true`）。
- **验收**：开启 cleanup 后日志包含统计行；`CleanupStats` Bean 可注入查询；关闭开关后无额外日志。
- **预估**：1 人天。

### T10 元数据迁移工具（G5）

- **涉及文件**：core 新增 `TaskStoreMigrator`（源/目标均为 `TaskStore` 泛型）；`upload-file-store-jdbc` /
  `upload-file-store-redis` 提供 `main` 或工厂方法；starter 暴露迁移 Bean（`@ConditionalOnProperty`）。
- **方案**：按 `TaskStore.list()` 逐个 `get` → `save` 到目标，进行中任务原样保留（含 `uploadedChunks` 与
  merge 状态）；支持 `FileTaskStore → JdbcTaskStore`、`FileTaskStore → RedisTaskStore`；迁移前校验
  `schemaVersion` 兼容。提供命令行入口与程序化 API，**不自动执行**。
- **配置**：`upload-file.migration.enabled`（默认 `false`）+ 目标端复用 `metadata-store` 选择。
- **验收**：构造含进行中任务与已完成任务的源存储，迁移后目标端 `get/list` 与源一致；迁移失败可重入（幂等）。
- **预估**：1 人天。

### T11 元数据格式版本（G6）

- **涉及文件**：`UploadTask` 新增 `schemaVersion` 字段；`FileTaskStore` 读写；加载时兜底。
- **方案**：当前格式定为 `schemaVersion=1`；旧 JSON 缺字段按 `1` 处理；未来格式变更前必须升级逻辑并给出
  迁移路径（结合 T10）。
- **验收**：rc.2 生成的 JSON 读取后 `schemaVersion=1`；写出后含该字段；旧版本读取新 JSON 不受影响（Gson 忽略未知字段）。
- **预估**：0.5 人天。

### T12 compat 回归套件与升级/回滚矩阵（G7）

- **涉及文件**：新增 `compat` 测试源集（src/test 或独立模块）；docs 新增升级/回滚矩阵。
- **方案**：
  - 以「rc.2 生成的 JSON 元数据样例 + rc.2 目录布局（chunks/、files/）」启动，验证 progress/merge/download
    在**默认配置**下行为一致；
  - C1（TTL 静默删存量任务）、C2（内存存储 + 孤儿 GC 启动即删）高危组合回归用例；
  - 校验 T6–T11 所有新开关「关闭态」不引入线程、不改变既有路径；
  - docs 补充 rc.1 → rc.2 → rc.3 升级与回滚矩阵（配置清单、默认值变更、回滚注意事项）。
- **验收**：`mvn verify` 包含 compat 套件并全绿；矩阵文档覆盖 G1–G8 的默认值与回滚影响。
- **预估**：1 人天。

### T13 V1.0.0 范围定义（SOW）（G8）

- **涉及文件**：新增 `docs/PLAN-V1.0.0.md`（中英双语）；README 发布前更新。
- **方案**：明确 1.0.0 GA 范围：
  - **包含**：T1–T13 全部能力（清理/原子合并/异步合并/JDBC·Redis 存储/访问控制/大小配额/迁移/观测）；
  - **显式排除并声明为后续版本**：秒传、整文件一致性校验（SHA-256）、去重、tus、多语言 SDK、对象存储后端、
    回收站、审计日志、病毒扫描、预签名分享、版本管理、加密、压缩、预览/转码、Webhook；
  - **生产约束声明**：默认单实例/共享盘；无内建鉴权时须由网关/反向代理兜底；chunk 本地盘不支持跨节点合并；
  - **API 冻结**：rc.3 后不再做破坏性变更，`1.0.0` 起遵循语义化版本。
- **验收**：SOW 文档评审通过，作为 1.0.0 发布公告依据。
- **预估**：0.5 人天。

## 四、新增配置项清单（前缀 `upload-file`）

| 配置项 | 默认值 | 说明 | 关联任务 |
| --- | --- | --- | --- |
| `security.enabled` | `false` | 启用访问控制校验 | T6 |
| `security.token` | *(空)* | 共享令牌；空 = 不校验 | T6 |
| `security.header-name` | `X-Access-Token` | 令牌请求头名称（也接受同名列查询参数） | T6 |
| `max-file-size` | `-1` | 单文件总大小上限（字节），-1 无限制 | T8 |
| `quota.max-bytes` | `-1` | 全局容量配额（字节），-1 关闭 | T8 |
| `cleanup.use-redis-lock` | `false` | 多实例清理调度使用 Redis 租约锁 | T7 |
| `observability.log-stats` | `true` | 清理统计结构化日志 | T9 |
| `migration.enabled` | `false` | 暴露迁移 Bean（不自动执行） | T10 |

纯 Servlet 场景对应新增 init-param（`UploadFileContext` 解析，命名与上表一致，如 `security.token`、
`max-file-size`、`cleanup.use-redis-lock`）。

## 五、兼容性说明

- T6–T11 全部默认关闭 / 空值 / `-1`，升级 rc.2 → rc.3 零行为变更（由 T12 compat 套件回归保证）；
- `UploadTask` 新增 `schemaVersion` 缺省按 `1`，旧 JSON 可读可写，回滚兼容；
- T7 调度锁仅影响「开启且多实例」场景；T10 迁移为显式工具，不自动运行；
- 1.0.0 起冻结 API：rc.3 的 SPI 变更（如 `AccessControl`）为纯新增，不破坏既有自定义实现。

## 六、测试计划

- 单元测试沿用 JUnit 4 + JaCoCo，core 行覆盖率不低于当前；新增覆盖：401 鉴权、大小/配额拒绝、`CleanupStats`、
  迁移幂等、`schemaVersion` 兜底；
- 新增 `compat` 回归套件（rc.2 数据样例 + 默认配置 + C1/C2 高危用例），纳入 `mvn verify`；
- `example/upload-file-demo` 增加 security / quota 配置示例并手工验证前端流程；
- 发布前执行 `mvn verify`（gpg 仅在 release profile）与 `mvn jacoco:report`。

## 七、文档与示例更新

- `README(.zh-CN).md`：新配置项、访问控制用法、多实例约束、迁移工具用法；
- `docs/API(.zh-CN).md`：`401` 响应、大小/配额 `400`/`507` 说明；
- `docs/DESIGN(.zh-CN).md`：新增 `AccessControl`、`CleanupStats`、`TaskStoreMigrator` 组件职责；
- `docs/ROADMAP(.zh-CN).md`：标记 rc.3 计划与 1.0.0 范围；
- **新增** `docs/PLAN-V1.0.0.md`（SOW，T13）与升级/回滚矩阵（T12）；
- 示例 `application.yml` / servlet `web.xml` 补充新配置示例。

## 八、里程碑与发布

1. **M1**：T6 访问控制 + T8 大小配额（入口防护先行）；
2. **M2**：T10 迁移工具 + T11 `schemaVersion`（数据层完备）；
3. **M3**：T9 可观测 + T7 多实例约束（运维闭环）；
4. **M4**：T12 compat 回归 + T13 SOW 文档（质量与发布依据）；
5. **M5**：`1.0.0-rc.3 → 1.0.0`，按既有 release profile 发布 Maven Central，发布公告引用 SOW 与升级/回滚矩阵。
