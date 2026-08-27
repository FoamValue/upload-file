# V1.0.0-rc.2 新特性分析与 rc.1 兼容性评估

> 本文档基于 [V1.0.0-rc.2 任务开发计划](PLAN-V1.0.0-rc.2.zh-CN.md) 与当前 `1.0.0-rc.1` 代码基线编写。
> 目的：评估 rc.2 在「rc.1 已大规模上线」前提下的升级冲突、兼容策略，并补充计划中遗漏的必须考虑事项。
>
> ✅ **状态：本分析建议的默认值与会话护栏已在 `1.0.0-rc.2` 落地** —— `cleanup.*`/`async-merge.*` 默认关闭、提供 `merge.atomic` 回退开关、内存存储跳过孤儿清理、上传/清理通过共享 `IdentifierLock` 协调。

## 0. rc.1 现状基线（分析依据）

rc.1 已上线能力盘点（以代码为准）：

- 分片上传 / 进度查询 / 合并，`UploadServlet` 提供 `POST /upload`（分片）、`action=merge`、`action=progress` 三个动作（`upload-file-servlet/.../UploadServlet.java:68-131`）；
- 断点续传下载：`ResumableDownloadService` 基于 `TaskStore` + `mergedFileDir` 解析文件（`upload-file-core/.../ResumableDownloadService.java:41-64`）；
- 元数据存储 SPI：`TaskStore`（`get/save/remove/list`），内置 `MemoryTaskStore` 与 `FileTaskStore`（每任务一个 JSON，临时文件 + 原子改名，带进程内缓存 `cache`）；
- 分片存储 SPI：`ChunkStorage`，内置 `LocalFileChunkStorage`（`<root>/<identifier>/<chunkIndex>.part`）；
- 合并逻辑：`ResumableUploadService.merge()` 直接写入 `files/<identifier>/<fileName>`，**非原子**，中途失败可能残留坏文件（`ResumableUploadService.java:154-203`）；
- `UploadTask` 序列化字段：`identifier/fileName/fileSize/chunkSize/chunkTotal/uploadedChunks/merged/finalPath/finalFileSize/createTime/updateTime`（`UploadTask.java:15-30`）；
- Starter 配置（前缀 `upload-file`）：`storageDir/metadataDir/verifyChecksum/uploadUrl/downloadUrl/maxChunkSize/maxRequestSize`（`UploadFileProperties.java:14-37`），`TaskStore` 按「有 metadataDir → file，否则 memory」条件装配（`UploadFileAutoConfiguration.java:42-49`）。

rc.2 计划新增：T1 过期任务清理（TTL/GC）、T2 原子合并、T3 孤儿数据 GC、T4 异步合并、T5 可插拔元数据存储（JDBC/Redis），以及一组 `upload-file.*` 新配置项。

---

## 1. 思考一：rc.1 已大规模使用，rc.2 发布是否存在巨大冲突

### 1.1 数据兼容（结论：基本兼容，存在两处高危）

| 项 | 分析 | 风险 |
| --- | --- | --- |
| 旧 JSON 元数据读取 | rc.2 为 `UploadTask` 新增 `mergeState/mergeError/mergeStartedAt`，Gson 反序列化缺字段为 null，计划中「按 NONE 处理」与现有 `uploadedChunks` 兜底一致（`FileTaskStore.java:76-89`）。**兼容。** | 低 |
| 反向读取（回滚） | rc.2 写出的 JSON 含新字段，rc.1 的 Gson 默认忽略未知字段，可读。但若 rc.2 已开启异步合并，回滚后 PENDING/RUNNING 状态无人推进，任务卡住（可重发 merge 恢复）。**兼容但需文档。** | 低 |
| 存量未完成任务 | rc.1 环境存在大量「进行中/长期未动」的任务，其元数据无新字段，rc.2 默认 `merge()` 同步入口仍可用。**兼容。** | 低 |
| T1 TTL 清理 vs 存量任务 | 若 `cleanup.enabled` 按计划默认 `true` + `task-ttl=24h`，**升级后超过 24h 未更新的进行中任务会被静默删除**，断点续传直接失效。这在 rc.1 中从不发生，是「默认行为变更」。**高危。** | **高** |
| T3 孤儿清理 vs MemoryTaskStore | 若 `cleanup.orphan-enabled` 按计划默认 `true`，而生产用的是 `MemoryTaskStore`（重启后 `list()` 为空），`run-on-startup` 扫描会把磁盘上**全部** chunk 目录与已合并文件判为孤儿删除。**启动即删库，数据不可恢复。** | **高** |

### 1.2 配置兼容（结论：无键名冲突，但默认值安全）

- rc.2 新增配置项均为全新键名（`merge.fsync/cleanup.*/async-merge.*/metadata-store/jdbc.*/redis.*`），与 rc.1 的 8 个已有键无冲突；Spring Boot `@ConfigurationProperties` 对未知属性默认忽略，**旧配置升级后原样生效**。低风险。
- **风险在默认值**：见 1.1 两处高危与第 2 节开关矩阵。
- 纯 Servlet 场景新增 init-param 与 properties 同名，旧 `web.xml` 不含新参数 → 取默认值。默认值是否安全决定成败。

### 1.3 接口 / API 兼容（结论：兼容）

- 既有三个 action 全部保留；T4 只**新增** `mergeAsync` / `mergeStatus`，不影响旧前端。低风险。
- `ChunkStorage` 新增 `listIdentifiers()` 为 **default 方法**（返回空集），现有自定义实现零改动。低风险。
- `merge()` 同步入口保留（默认），异步为 opt-in。低风险。

### 1.4 行为兼容（结论：T4/T5 兼容，T2/T3 需处理，T1 需改默认值）

- **T1**：默认开启即改变旧行为（静默删任务）。→ 需默认关闭。
- **T2**：原子合并没有开关，属于 merge 实现方式的内改。对外契约一致（成功路径产物相同、失败路径从「留坏文件」变为「删坏文件」），客户端无感知，属安全增强。**但必须做并发/竞态验证**（见 2.4）。中风险。
- **T3**：默认开启 + 两个高危组合（MemoryTaskStore、多实例、merge 并发竞态）。→ 需默认关闭 + 保护。
- **T4**：默认关闭，开关开启后的「RUNNING/SUCCEEDED 拒收新分片」属预期变更，可控。低风险。
- **T5**：`metadata-store=auto` 完全复刻 rc.1 行为。低风险。真正的坑在**迁移**（见 3.1）与**多实例**（见 3.3）。

### 1.5 风险汇总

| 编号 | 冲突点 | 等级 | 触发条件 |
| --- | --- | --- | --- |
| C1 | T1 默认开启静默清理长期未动任务 | 高 | 升级不调整配置 |
| C2 | T3 + MemoryTaskStore + run-on-startup = 启动即删 | 高 | 默认开启且用内存存储 |
| C3 | T3 与并发 merge/上传的竞态误删活跃数据 | 中 | 开启 orphan 清理 |
| C4 | 多实例部署重复调度 / 元数据集中后 chunk 本地不一致 | 中 | 多实例场景 |
| C5 | FileTaskStore 进程内缓存与多进程写不一致 | 中 | 共享 metadata 目录多进程 |
| C6 | 回滚时异步任务状态卡死 | 低 | 开启 async 后回滚 |
| C7 | 存量坏文件（rc.1 非原子 merge 残留）在 rc.2 被判定为孤儿 | 低 | 开启 orphan 清理 |

---

## 2. 思考二：rc.2 能否兼容 rc.1 生产环境（默认关闭 / 不启动新特性）

### 2.1 总体结论

**原则可行，但计划中的默认值违背了「默认不启动新特性」的目标，必须修正后才能做到「升级即兼容」。**

兼容策略成立的核心前提：
1. 所有 rc.2 新特性可被配置开关**完全关闭**，关闭后行为与 rc.1 逐字节一致；
2. 接口演进只增不改（新增 action、新增 default 方法、同步 merge 保留）；
3. 旧元数据向前兼容（新增字段缺省兜底）。

### 2.2 开关矩阵（现状 vs 建议）

| 新特性 | 配置项 | 计划默认值 | 建议默认值 | 理由 |
| --- | --- | --- | --- | --- |
| T2 原子合并 | `merge.fsync` | `true` | `true`（保留） | 仅性能，不改变对外行为 |
| T1 过期清理 | `cleanup.enabled` | `true` | **`false`** | 默认开启 = 静默删存量任务（C1），违背兼容目标 |
| T1 | `cleanup.task-ttl` | `24h` | 随 enabled 关闭无影响；开启时建议放宽 | 大文件/长期暂停任务场景 |
| T3 孤儿 GC | `cleanup.orphan-enabled` | `true` | **`false`** | 最高误删风险（C2/C3），且必须配合持久化 TaskStore |
| T3 | `cleanup.run-on-startup` | `true` | `false`（并仅随 orphan-enabled） | 启动即扫描 = 升级首启即删 |
| T1/T3 | `cleanup.interval` | `1h` | 无影响 | 跟随 enabled |
| T4 异步合并 | `async-merge.enabled` | `false` | `false`（保留） | 已是安全默认 |
| T5 存储 | `metadata-store` | `auto` | `auto`（保留） | 完全复刻 rc.1 装配行为 |

### 2.3 无开关的内改项（T2 原子合并）

T2 无法通过开关「关掉」。建议二选一：
- 接受该安全增强，在 CHANGELOG 中声明「merge 失败由残留坏文件变为删除临时文件」，并提供升级说明；
- 或增加 `merge.atomic` 回退开关（默认 `true`），供极端保守的存量用户切回 rc.1 直写逻辑。**建议实现该开关**，成本低，可彻底消除升级疑虑。

### 2.4 兼容性验证方式

- 新增 `compat` 测试套件：以「rc.1 生成的 JSON 元数据样例 + rc.1 目录布局（chunks/、files/）」启动 rc.2，验证 merge/progress/download 在**默认配置**下与 rc.1 行为一致；
- 校验所有新配置「关闭态」不引入任何调度线程、不改变任何既有路径；
- 引入内存/文件两种 TaskStore 的升级冒烟测试。

---

## 3. 思考三：被遗漏、必须考虑的内容

### 3.1 数据迁移（T5，计划未覆盖）

- rc.1 FileTaskStore 的存量 JSON（含进行中任务）→ JDBC/Redis，需要**迁移工具或脚本**，否则切换 `metadata-store` 后所有进行中任务丢失（断点续传失效）。
- `UploadTask` 新增字段建议同步引入 `schemaVersion`/元数据格式版本字段，为后续演进与迁移判断留退路。

### 3.2 并发与一致性（T3/T1/T2 交叉）

- **清理 vs 合并竞态**：merge 写临时文件 `files/<id>/<fileName>.merge-*.tmp`，孤儿扫描若不能区分「正在使用的临时文件」与「残留」，会删掉正在写入的文件导致 merge 失败。扫描必须排除活跃临时文件（按时间戳/活跃标记），且清理过程应持有与 merge/上传相同的 per-identifier 锁。
- **清理 vs 上传竞态**：chunk 正在写入 `.upload-*.part` 时被当孤儿删除。同上处理。
- **FileTaskStore 缓存**：`get()` 命中 `cache` 不读盘（`FileTaskStore.java:67-70`），多进程共享 metadata 目录会出现缓存与磁盘不一致。rc.2 需明确「FileTaskStore 仅单进程」约束，或改为缓存失效策略。

### 3.3 部署形态（T1/T3/T5，计划未覆盖）

- **多实例**：starter 每个实例都会起 T1/T3 的调度器 → 重复清理、重复 GC。需 Leader 选举或部署层面关闭声明。
- **T5 + 本地 chunk**：元数据集中到 JDBC/Redis 后，若 chunk 仍是本地盘，横向扩容会让「在 A 上传的分片、在 B 合并」找不到分片。必须配套共享存储（NFS/OSS/S3）或明确「集中存储仅限单实例/共享盘」约束并写入文档。
- **多实例共享 metadata 目录 + FileTaskStore**：进程内缓存彼此不可见，同上 3.2。

### 3.4 可观测性与运维（计划未覆盖）

- T1/T3 后台任务应暴露：上次执行时间、清理条数、耗时、是否出错（JMX/metrics/日志），否则生产事故无法排查。
- T4 异步合并队列长度、线程池饱和告警。
- TTL=0 的语义必须实现为「永不清理」，并单测覆盖，避免 0 被误判为立即清理。

### 3.5 发布 / 回滚（计划未覆盖）

- 发布 `mvn verify` 时新增 `compat` 测试 + 两处高危组合的回归用例（C1/C2）；
- 提供 rc.1 → rc.2 升级与回滚矩阵文档：配置清单、默认值变更、数据无迁移声明、回滚注意事项（异步任务状态、孤儿清理数据不可逆）；
- **孤儿清理数据不可恢复**：任何 enabled 场景必须在文档与代码中提示「先备份磁盘数据」。

### 3.6 其他细节

- `Files.move` 的 `ATOMIC_MOVE` 在部分文件系统抛 `AtomicMoveNotSupportedException`，T2 需沿用 rc.1 已有回退逻辑（`LocalFileChunkStorage.java:70-75`）；
- fsync 建议对临时文件 `FileChannel.force(true)`，必要时同步 fsync 父目录，否则 ATOMIC_MOVE 的持久性在部分平台不保证；
- 新模块 `upload-file-store-jdbc/redis` 的依赖（H2 仅测试、Jedis）与 license/安全审计需在发布前确认；release profile 需覆盖新模块。

---

## 4. 结论与建议

1. **不存在「巨大、不可调和」的冲突**，但存在两处**必须修正才能安全升级**的高危默认值：`cleanup.enabled`、`cleanup.orphan-enabled` 应默认 `false`（连同 `run-on-startup`），否则升级 rc.1 生产环境存在静默删任务 / 启动即删数据的风险。
2. 「开关默认关闭、默认不启动新特性」的兼容路线**成立**，前提是：T1/T3 默认关闭、T4 默认关闭、`metadata-store=auto`、同步 merge 保留、接口只增不改。
3. 建议为 T2 提供 `merge.atomic` 回退开关，彻底消除「无开关内改项」的升级疑虑。
4. 计划需补充：存量数据迁移工具（FileTaskStore → JDBC/Redis）、清理与合并/上传的锁协调、多实例约束、可观测性、升级/回滚矩阵、compat 回归测试。

### 发布前 checklist

- [ ] 修正 `cleanup.enabled` / `cleanup.orphan-enabled` / `cleanup.run-on-startup` 默认值为 `false`；
- [ ] 评估并实现 `merge.atomic` 回退开关；
- [ ] T3 扫描排除活跃临时文件，并与 per-identifier 锁协调；
- [ ] T5 提供 FileTaskStore → JDBC/Redis 迁移工具/文档；
- [ ] 明确并文档化多实例部署约束（Leader 调度、共享存储）；
- [ ] 新增 `compat` 测试套件（rc.1 数据/布局 + 默认配置回归）与 C1/C2 用例；
- [ ] 编写 rc.1 → rc.2 升级与回滚矩阵文档；
- [ ] 新增 `schemaVersion` 字段与 TTL=0 语义单测。
