# upload-file 组件使用反馈（供组件升级优化参考）

> 本文件由组件使用者（PathFinder 文件管理系统）整理，用于向 `cn.chenxinjie:upload-file`
> 组件反馈真实集成中的使用情况、遇到的缺陷与增强诉求，供组件侧评估与升级优化。
> 本工程侧**不修改组件代码**；仅按现状如实反馈。

## 1. 评审对象与接入环境

| 项 | 内容 |
|---|---|
| 集成项目 | path-finder（文件管理系统，单组织私有部署） |
| 集成时点 | 组件 `1.0.0-rc.3`；path-finder 仓库 commit `62ae062`（main） |
| 运行环境 | JDK 26 · Spring Boot 4.1.1 · Spring Security 7.1.1 · Redis 9（部署）/ 本地 Redis 7 · MySQL 8 |
| 实际引用产物 | `upload-file-core:1.0.0-rc.3` + `upload-file-store-redis:1.0.0-rc.3`（core 手工装配） |
| 未引用产物 | `upload-file-spring-boot-starter`、`upload-file-servlet`（根因：二者依赖 `javax.servlet`，与 Spring Boot 4 / jakarta 不兼容） |
| 前端协议 | 浏览器端分片上传：`POST /upload`（multipart `file` + `identifier/fileName/fileSize/chunkSize/chunkTotal/chunkIndex/chunkMd5`）、`GET /upload?action=progress|mergeStatus`、`POST /upload?action=mergeAsync|merge` |
| 业务形态 | 分片（5MB）→ MD5 校验 → 断点续传（跳过已传分片）→ 异步合并轮询 → confirm 入库；文件最终脱离组件存储进入业务统一存储（UUID + 日期分目录） |

组件接入点（后端）：
- `config/UploadFileConfig.java`：TaskStore（memory/file/redis 可切换）、`LocalFileChunkStorage`、`ResumableUploadService` 手工装配，并设置最大分片/文件、fsync、原子改名、per-identifier 锁、异步合并线程池；
- `controller/UploadController.java`：按组件协议暴露 `/upload`（分片/merge/mergeAsync/mergeStatus/progress）；
- `service/FileService.confirm()`：合并完成后按组件产物路径把文件 `Files.move` 到业务存储并回填 MD5；
- `controller/FileController.download()`：业务下载自行实现 Range 流式（未复用组件下载能力）；
- `config/StorageCleanupScheduler.java`：业务侧自建调度清理（仅回收站 + DB 侧 UPLOADING 孤儿行）；
- 前端 `utils/uploadTask.ts`、`components/UploadModal.tsx`：分片上传主流程。

---

## 2. 组件能力使用总览

图例：✔ 已用（生产） ｜ ◐ 部分/变通使用 ｜ ✖ 未使用 ｜ N/A 不适用

| 组件能力 | 使用 | 说明与证据 |
|---|---|---|
| 分片上传 `uploadChunk(req, in)` | ✔ | 仅用无 token 变体；token 变体未用 |
| 断点续传（进度跳过已传分片） | ✔ | 前端 `getProgress().uploadedChunks` 跳过已传分片 |
| 分片 MD5 校验（`verify-checksum`） | ✔ | 后端开 `verify-checksum=true`；异常映射 400 友好提示 |
| 文件/分片大小限制（`setMaxFileBytes/setMaxChunkBytes`） | ✔ | 配置 `max-file-size=500MB`、`max-chunk-size=5MB` |
| 同步合并 `merge()` | ◐ | 服务端暴露了 `action=merge`，但前端业务只用异步合并 |
| 异步合并 `submitMerge()/getMergeStatus()` | ✔ | 前端轮询 `NONE→RUNNING→SUCCEEDED/FAILED` |
| 合并产物定位（`finalPath`） | ✖ | 业务侧按目录约定猜路径，见 §4-1 |
| 断点下载 `ResumableDownloadService` | ✖ | 业务下载自行实现 Range，见 §4-4 |
| 官方下载端点 `DownloadServlet`/`GET /download` | ✖ | 未接线（javax 阻塞 + 业务已自实现） |
| Range 解析工具 `DownloadRange.parse` | ✖ | 业务侧另写正则解析 |
| 过期任务/孤儿清理 `StorageCleanupService` | ✖ | 未接线；`cleanup.*` 配置为死配置，见 §4-2 |
| 清理锁 `CleanupLock`（含 redis 实现） | ✖ | 未使用 |
| AccessControl / `TokenAccessControl` | ✖ | 恒用 `PermitAllAccessControl` + 外部 Spring Security 兜底，见 §4-5 |
| 全局配额 `setMaxTotalBytes`（507） | ✖ | 未启用；业务存储监控独立于组件 |
| 多 TaskStore | ◐ | redis（生产）/ memory（测试）已用；file（`FileTaskStore`）分支存在但未用；jdbc 为组件独立 store 模块，本工程未引入 |
| `TaskStoreMigrator`（store 迁移） | ✖ | 未使用 |
| `isChunkUploaded(identifier, i)` | ✖ | 未用（前端靠 `uploadedChunks` 集合） |
| 组件工具 `ChecksumUtil.md5` | ✔ | confirm 回填与目录同步校验复用，未重复造轮子 |
| 组件异常类型（`ChecksumMismatchException` 等） | ◐ | 仅映射了校验异常；其余被业务兜底为 500，见 §4-6 |
| 官方 starter/servlet 自动注册 | ✖ | javax 阻塞，见 §3-1 与 §6 |

---

## 3. 用得好的地方（可沉淀为官方示例/文档）

1. **core 手工装配面小、语义清晰**。starter 因 `javax.servlet` 与 Spring Boot 4（jakarta）不兼容被降级后，
   `UploadFileConfig` 仅用少量 Bean 即完成了 TaskStore、ChunkStorage、核心服务、异步合并池的装配，
   对外协议与组件 README 保持一致，后续换回官方 starter 不需要改前端与协议。
2. **关键安全/一致性开关全开且默认正确**。`verify-checksum=true`（分片校验）、`mergeAtomic`（临时文件 + 原子改名）、
   `mergeFsync`（落盘后再改名）在组件默认值即为 true，工程零配置即获得防脏文件与崩溃一致性。
3. **per-identifier 串行锁 + 元数据一致性校验非常好用**。组件对同一 identifier 的分片并发与
   `chunkTotal/chunkSize/fileSize/fileName` 一致性做了强校验（后续分片与首个分片不一致会被拒绝），
   工程侧因此省去大量自校验逻辑。
4. **异步合并状态机完整**。`submitMerge` 幂等（PENDING/RUNNING 重复提交直接返回状态）、执行器拒绝时回滚状态、
   失败记录 `mergeError`，前端轮询语义明确，异常无需后端额外兜底。
5. **MD5 工具被复用而非重写**。业务 confirm 与"目录同步扫描"均复用 `ChecksumUtil.md5` 计算全文件摘要。
6. **store 层抽象利于测试**。集成测试用 `memory` store、生产用 `redis` store，无需改业务代码即可切换。
7. **confirm 幂等 + 同盘原子迁库**。合并产物与业务存储同盘，`Files.move` 后回填 `fileMd5/fileSize/status`，
   并允许重复 confirm（READY 直接返回），对前端重试友好。
8. **前端分片流程实现规范、贴近组件语义**：`uploadTask.ts` 把组件返回的**裸 JSON**与业务 `{code,message,data}`
   包装区分对待（组件端点不走统一响应封装），避免误解析；进度条按"分片阶段 0~90% → 提交合并 92% → 轮询 → 97% → confirm 100%"
   与状态机一一对应；断点续传/秒传依赖 `uploadedChunks` 跳过已传分片，行为与组件一致。

---

## 4. 用得不好 / 有风险的地方

### 4-1（P0）合并产物定位靠"目录约定猜路径"，未使用组件返回的 finalPath

组件在 `UploadResult.finalPath` / `MergeStatus.finalPath` / `UploadTask.finalPath` 中已提供合并产物的绝对路径，
但工程 `FileService.confirm()` 未回读该结果，而是自行按
`{storage-dir}/files/{identifier}/{originalName}`（`resolveMergedPath`）**推导**路径后 `Files.move` 入库。

- 现象/风险：路径推导依赖"identifier 目录名 + 原始文件名"的内部约定，属弱契约；
  一旦组件调整合并产物目录结构、或文件名落盘策略（去重/截断/安全改写）变化，下游 confirm 即静默失效，
  且报错时难以定位根因（现在表现为"合并产物不存在"）。
- 期望：提供稳定的"按 identifier 取任务/产物"读接口（见 §7 P0-2），或明确把 finalPath 读取方式固化为契约并给出示例。

### 4-2（P0）组件 StorageCleanupService 未接线，磁盘孤儿无法回收

- 工程自建 `StorageCleanupScheduler` 仅做两件事：回收站到期清除、删除 DB 中超过 24h 的 `UPLOADING` 记录；
  **不清理任何组件侧磁盘数据**。
- 由于工程未接线组件的 `StorageCleanupService`，两类磁盘孤儿会长期残留：
  1. 上传中断/放弃后残留的分片目录（`{storage-dir}/chunks/…`）；Redis 元数据到期（TTL 24h）只清索引、不清磁盘；
  2. **合并成功但未 confirm（或 confirm 前服务重启）的大文件**：合并产物已写入 `{storage-dir}/files/{identifier}/…`，
     而 DB `UPLOADING` 记录 24h 后被业务调度删除，产物文件成孤儿（可能数百 MB/个）。
- 工程 `application.yml` 中 `upload-file.cleanup.*`（enabled/run-on-startup/interval/task-ttl/orphan-enabled/use-redis-lock）
  全部为**死配置**（手工装配未消费），容易给运维"已开启清理"的错觉。
- 期望：见 §7 P0-3（cleanup 开箱即用）与 P0-2（提供显式删除/取消任务 API）。

### 4-3（P1）异步合并相关配置"形同虚设"

`application.yml` 中 `upload-file.async-merge.enabled`、`async-merge.thread-pool-size` 不会被手工装配读取：
`UploadFileConfig` 无条件创建 `newFixedThreadPool(2)` 并 `setAsyncExecutor(...)`。
- 现象：想"关掉异步合并"或调整线程数改配置无效；想用同步 `merge` 也不可行（服务始终具备异步能力）。
- 期望：异步开关/线程数做成组件可读配置，或至少在 README 写明 core 手工装配时这些属性由调用方自理。

### 4-4（P1）下载端重复实现（Range/断点下载），且与组件语义存在细节差

业务下载（`/api/file/download/{token}`）因文件已脱离组件存储（进入业务统一存储），下载无法直接复用组件
`ResumableDownloadService`/`DownloadServlet`（其按 `identifier` 查合并产物）。但工程因此**另写了一套**：
正则解析 `Range`、`skipNBytes` 流式、200/206/416 分支、Content-Range/Content-Length、文件名 UTF-8 编码、
扩展名→Content-Type 映射。
- 风险：Range 边界语义（多段 Range、非法 Range、`If-Range` 等）在自实现中难以做全；与组件 `DownloadRange.parse`
  行为可能不一致。
- 期望：组件若提供 jakarta 版 servlet/starter，工程可整体回归组件下载端点（承接临时产物兜底下载）；
  或组件把 `DownloadRange.parse` 等解析器文档化为可单独复用的公共工具。

### 4-5（P1）AccessControl 能力被"架空"

- 工程 `UploadFileConfig` 恒用 `PermitAllAccessControl.INSTANCE`，且所有 `/upload` 请求从不携带组件侧 token 参数/头；
  接口鉴权完全由工程自己的 Spring Security `TokenAuthFilter`（`Authorization: Bearer`）前置完成。
- 结果：组件 `AccessControl` SPI、`TokenAccessControl`、`security.enabled/token` 配置对工程完全不可见/不可用，
  对组件而言"有安全能力但集成方无法利用"。
- 期望：见 §7 P1-7（提供与外部认证体系对接的示例/SPI 文档），避免接入方要么裸奔、要么在组件外加一层。

### 4-6（P1）HTTP 层错误语义被业务兜底吞掉

`UploadController` 直接调用 service 并把对象/异常抛给 `GlobalExceptionHandler`：
- `ChecksumMismatchException` 被专门映射为 400（唯一被善待的组件异常）；
- 其余组件异常（如合并缺分片 `IllegalStateException`、任务不存在 `NoSuchElementException`、参数非法
  `IllegalArgumentException`）落入 `Exception` 兜底返回 **HTTP 500 "系统内部错误: …"**，把"客户端重试/补传可恢复"
  的场景误报成服务端故障。
- 官方 `UploadServlet` 对相同情况返回 400/401/507 且 JSON 化，说明语义本可更准，只因 javax 无法直接用，
  工程只能降级到"能跑但语义差"。
- 期望：见 §7 P1-4。

### 4-7（P2）响应体信封不统一，前端需特殊分支

组件端点返回的是对象裸 JSON（`UploadProgress`/`MergeStatus`/`UploadResult`），而业务接口统一为
`{code,message,data}`。前端不得不对组件端点绕过统一响应拦截（`uploadTask.ts` 单独 fetch + 判 `resp.ok`）。
- 影响小但增加集成心智负担；若组件未来提供"可选包装"或统一错误结构（含 code/message），可消除该特判。

### 4-8（P2）"秒传"实际只对"同一 identifier 的重复上传"生效

identifier 由工程服务端随机生成（非内容寻址），因此：
- 同一文件跨用户/跨会话再次上传会生成新 identifier，无法复用已上传分片/产物，"秒传"仅在同一上传任务内有效；
- 服务端随机 identifier + 断点续传的"进度跳过"组合是安全的（客户端无法猜别人 identifier），但对产品语义而言，
  "真正秒传（内容去重）"仍需业务侧额外实现。
- 期望：若组件考虑支持内容寻址/秒传的顶层能力或示例（`identifier = f(md5)`），可作为组件特性文档补充（非缺陷）。

---

## 5. 未使用到的能力清单（补充总览）

| 能力 | 未用原因 / 备注 |
|---|---|
| `ResumableDownloadService` + `DownloadServlet`（`/download`，Range） | javax 无法接线；业务下载已自实现并脱离组件存储 |
| `StorageCleanupService` / `CleanupLock` / Redis 清理锁 / cleanup 统计监听 | 未接线；属最值得补齐的运行时能力（见 4-2） |
| `AccessControl` / `TokenAccessControl`（`X-Access-Token`/`token` 参数通道） | 组件外已有认证层，见 4-5 |
| `setMaxTotalBytes` 全局配额（507） | 业务按"磁盘实际容量"另行监控；组件配额能力未启用 |
| `FileTaskStore` / `JdbcTaskStore` / `TaskStoreMigrator` | file（`FileTaskStore`）分支存在但未使用；jdbc 模块未引入；store 迁移暂无场景 |
| `uploadChunk/getProgress/merge/getMergeStatus/submitMerge` 的 **token 重载** | 使用 PermitAll，token 恒空 |
| `isChunkUploaded(identifier, i)` | 前端按 `uploadedChunks` 集合判断即可，无需该查询 |
| 官方 starter/servlet 自动配置（servlet 注册、属性绑定、cleanup/accessControl/executor 自动装配） | javax 阻塞，见 §3/§6 |

---

## 6. 阻碍集成方"无脑使用"的根因（请组件优先正视）

1. **jakarta 兼容性**：`upload-file-spring-boot-starter` 与 `upload-file-servlet` 均依赖 `javax.servlet`，
   与 Spring Boot 3/4 生态（jakarta.servlet）完全不兼容。这是本工程降级为 `core` 手工装配、进而产生
   §4-1/4-2/4-4/4-6 一系列"重复实现/配置失效"问题的**唯一根因**。
   - 若组件提供 jakarta 版 starter（含 servlet 注册、属性绑定、cleanup、AccessControl、异步池自动装配），
     §4 中大半问题可随之消失。
2. **产物定位契约不明确**（见 4-1）：合并产物路径没有成为稳定的读接口，导致下游猜测内部目录结构。
3. **cleanup 不是"默认就绪"的能力**（见 4-2）：需要调用方自行组装/理解参数，否则留下磁盘孤儿。

---

## 7. 组件升级与优化建议清单

按对集成方收益排序（P0 影响生产数据安全/必用，P1 常规优化，P2 锦上添花）。

### P0
1. **提供 jakarta（Spring Boot 3/4）版 starter 与 servlet 产物**（可并列保留 javax 版做兼容）。
   建议至少覆盖：servlet 注册（`/upload`、`/download`）、`UploadFileProperties` 全量属性绑定、
   cleanup / AccessControl / 异步合并池 / 多 store 的自动装配；并补充 Spring Boot 3/4 + JDK 21+ 的 POC/示例。
2. **固化"按 identifier 取任务/合并产物路径"的读契约**：
   - 在 `ResumableUploadService` 增加公开方法（如 `getTask(identifier)` / `findUploadedFile(identifier)`），
     或文档明确"confirm 阶段应通过 `getMergeStatus(identifier).finalPath` / TaskStore 读取最终路径"；
   - 顺带提供**显式清理/取消任务** API（删除 identifier 任务 + 分片 + 未 confirm 的合并产物），
     供业务在放弃上传/超时/删除场景调用，替代"靠 TTL 与调度器兜底"。
3. **StorageCleanupService 开箱即用化**：starter 默认装配；core 手工装配给出最小 Bean 示例；
   明确 orphan 语义（未完成任务分片 + 已合并未 confirm 产物）与各存储（file/redis）下的实现；
   建议清理时对"已合并产物"与"未完成任务分片"分类统计（`CleanupStats` 已有字段，补充文档）。

### P1
4. **HTTP 层错误语义统一**：官方 servlet 对上传/合并异常返回稳定状态码（400/401/507）与**结构化 JSON**
   （含 reason/identifier），避免下游因 javax 不能接入时被迫 500 兜底；core 层异常建议有稳定的
   `statusCode()` 语义供调用方映射。
5. **异步合并参数配置化**：`async-merge.enabled`/线程数由属性驱动；core 手工装配时给出"如何关闭异步、使用同步 merge"示例。
6. **quota 能力文档化**：说明 `setMaxTotalBytes` 的近似统计口径、与各 store 的关系、507 语义与前端处理示例。
7. **AccessControl 接入文档与示例**：展示两种典型用法——(a) 直接配 `TokenAccessControl`（token 通过
   `X-Access-Token` 头/`token` 参数）；(b) 对接已有登录态（Bearer/SSO）时如何实现 `AccessControl` 委托到业务会话。
8. **Range/下载解析器独立复用**：`DownloadRange.parse` 等无状态工具独立成可单独引用的类/文档，降低下游重复实现。

### P2
9. **Redis 元数据丢失后的任务恢复**：TTL 过期（工程 24h）后任务在磁盘仍可能有分片/产物，
   建议提供"扫描磁盘分片目录重建 `UploadTask`"的恢复手段，或至少文档明确"元数据过期即视为新上传、旧分片靠 cleanup 回收"
   以对齐 TC-UP-018 类场景。
10. **统一响应信封可选支持**：考虑 `{code,message,data}` 包装的可选开关，或统一组件 JSON 字段命名，
    减少前端对组件端点单独分支（见 4-7）。
11. **"内容寻址秒传"顶层示例**：把"`identifier = f(内容)` 的全局秒传"作为可选策略与安全注意点写入文档（见 4-8）。

---

## 8. 附：工程侧与组件属性面不一致清单（用于校验 starter 属性覆盖）

工程 `application.yml` 的 `upload-file.*` 中，**手工装配未消费（死配置）** 的键：
`cleanup.enabled`、`cleanup.run-on-startup`、`cleanup.interval`、`cleanup.task-ttl`、
`cleanup.orphan-enabled`、`cleanup.use-redis-lock`、`async-merge.enabled`、`async-merge.thread-pool-size`、
`max-request-size`、`security.enabled`。

若组件新版 starter 能完整消费以上属性，将显著提升"配置即所得"；建议在 README 中提供
"core 手工装配 vs starter 自动装配"的属性覆盖对照表。

---

*反馈整理：PathFinder 项目组 · 2026-09 · 依据 path-finder commit `62ae062` 与 upload-file `1.0.0-rc.3` 源码核对。*
