# V1.0.0-rc.2 任务开发计划

> 🇺🇸 [English](PLAN-V1.0.0-rc.2.md)
>
> 对应 [未来优化方向](ROADMAP.zh-CN.md) 中「完整优化列表」的 P0 项，目标版本 **V1.0.0-rc.2**。
>
> ✅ **状态：5 个 P0 项（T1–T5）已在 `1.0.0-rc.2` 全部实现并通过测试。** 除计划外，该版本还包含健壮性加固：merge 先持久化状态再删分片、上传与清理共享 `IdentifierLock`、分片元数据跨分片一致性校验、分片大小上限、以及 `MemoryTaskStore` 的 identifier 校验。

## 一、目标与范围

V1.0.0-rc.2 实现当前优化计划中的 5 项 P0（基础健壮性），P1/P2 项排期到后续版本：

| 编号 | P0 项 | 说明 |
| --- | --- | --- |
| T1 | 过期任务清理（TTL/GC） | 未完成任务与分片定时过期清理 |
| T2 | 合并原子化 | merge 写临时文件，成功后 rename，杜绝坏文件 |
| T3 | 孤儿数据治理 | 元数据与磁盘差异扫描，清理孤儿分片/文件 |
| T4 | 合并异步化 | merge 后台执行，避免阻塞 HTTP 请求 |
| T5 | 元数据扩展存储 | 新增 JDBC / Redis 版 TaskStore（可选模块） |

## 二、架构与约束（符合项目插件形式）

沿用现有模块化分层，保持 core 无框架依赖（JDK 8）：

```
upload-file (父 POM / 聚合器)
├── upload-file-core                        # 纯 Java，实现 T1~T4 核心逻辑
├── upload-file-servlet                     # Servlet 接入，新增 action 与 init-param 装配
├── upload-file-spring-boot-starter         # 自动配置，新增 upload-file.* 配置项
├── upload-file-store-jdbc   [新模块]        # 可选：JDBC TaskStore 插件
├── upload-file-store-redis  [新模块]        # 可选：Redis TaskStore 插件
└── example/…                               # 演示用例同步更新
```

约束：
- 所有业务逻辑进 core，servlet / starter 只做装配与配置映射；
- 新组件遵循 `@ConditionalOnMissingBean` / init-param 覆盖模式；
- 默认行为与现有接口向后兼容，新能力默认关闭或保持旧行为；
- 版本仍为 1.0.0-rc.x，允许 SPI 小改（如 `ChunkStorage` 增加 default 方法）。

## 三、任务拆解

### T1 过期任务清理（TTL/GC）

- **涉及文件**：core 新增 `StorageCleanupService`；`UploadTask`（复用 `updateTime`）；`FileTaskStore.list()`。
- **方案**：按 `UploadTask.updateTime` 判定，超过 TTL 的未完成任务 → 删除任务记录 + `ChunkStorage.deleteChunks`。调度用 JDK `ScheduledExecutorService`，core 提供 `start/stop/cleanup()` 生命周期方法。
- **配置**：`upload-file.cleanup.enabled`、`cleanup.interval`、`cleanup.task-ttl`（见第四节）。
- **验收**：过期任务与分片被删除；未过期任务保留；TTL=0 表示永不清理；无任务时不空转。
- **预估**：1 人天。

### T2 合并原子化

- **涉及文件**：`ResumableUploadService.merge()`（core）。
- **方案**：合并先写入同目录临时文件（`<fileName>.merge-<uuid>.tmp`），`FileChannel.force`（可选 fsync）后 `ATOMIC_MOVE` 到最终文件；任何异常删除临时文件；`files/<id>/` 下残留临时文件纳入 T3 孤儿清理。
- **配置**：`upload-file.merge.fsync`（默认 true）。
- **验收**：写入中途抛错时最终文件不存在、临时文件被清理；合并成功后元数据才更新；现有 merge 测试全绿。
- **预估**：0.5~1 人天。

### T3 孤儿数据治理

- **涉及文件**：core 新增 `StorageCleanupService`（与 T1 共用调度器）；`ChunkStorage` 增加 default 方法 `listIdentifiers()`（`LocalFileChunkStorage` 覆写为列目录）；`ResumableDownloadService`/mergedFileDir 目录对比。
- **方案**：周期 + 启动时扫描：chunk 根目录与 merged 文件目录中，不在 `TaskStore.list()` 范围内的目录视为孤儿并删除。
- **配置**：`upload-file.cleanup.orphan-enabled`、`cleanup.run-on-startup`。
- **验收**：构造孤儿分片目录与孤儿合并文件后运行清理即被删除；有任务记录的数据不动；自定义 `ChunkStorage`（未覆写 `listIdentifiers`）返回空集合时不误删。
- **预估**：1 人天。

### T4 合并异步化

- **涉及文件**：`ResumableUploadService`（新增 `submitMerge/getMergeStatus` 与可选 `ExecutorService`）；`UploadTask` 新增 `mergeState/mergeError/mergeStartedAt`；`UploadServlet` 新增 `action=mergeAsync`、`action=mergeStatus`；starter 装配线程池。
- **方案**：merge 状态机 `NONE → PENDING → RUNNING → SUCCEEDED/FAILED`；异步合并持同一 identifier 条纹锁；`merge()` 同步入口保留（默认）；异步开启时上传新分片被拒绝（RUNNING/SUCCEEDED 状态）。旧 JSON 元数据缺字段按 `NONE` 处理。
- **配置**：`upload-file.async-merge.enabled`、`async-merge.thread-pool-size`。
- **验收**：异步提交返回 202 + 状态；轮询状态可达终态；失败状态携带错误信息；关闭开关后行为与旧版一致；并发提交同 identifier 幂等。
- **预估**：2 人天。

### T5 元数据扩展存储

- **新模块**：
  - `upload-file-store-jdbc`：`JdbcTaskStore implements TaskStore`，表 `upload_task(identifier, data, create_time, update_time)`，JSON 序列化（复用 Gson），`initSql` 支持自动建表；测试用 H2。
  - `upload-file-store-redis`：`RedisTaskStore implements TaskStore`，基于 Jedis（core 不引第三方框架，Redis 客户端依赖留在该模块）。
- **starter 装配**：新增 `upload-file.metadata-store`（`auto|memory|file|jdbc|redis`，默认 `auto` 兼容旧行为：有 `metadata-dir` 用 file，否则 memory）；jdbc/redis 通过条件 Bean 装配，缺依赖/缺 DataSource 时回退并告警。
- **配置**：`metadata-store`、`jdbc.table-name`、`redis.key-prefix`、`redis.ttl-seconds`。
- **验收**：`JdbcTaskStore` CRUD/list 通过（H2 单测）；Redis 模块提供冒烟测试或文档化手工验证；starter 在仅引入 core+starter 时仍走默认存储不报错。
- **预估**：3 人天。

## 四、新增配置项清单（前缀 `upload-file`）

| 配置项 | 默认值 | 说明 | 关联任务 |
| --- | --- | --- | --- |
| `merge.fsync` | `true` | 合并落盘前 fsync，保证 rename 前数据持久 | T2 |
| `cleanup.enabled` | `true` | 启用 TTL/孤儿清理调度 | T1/T3 |
| `cleanup.run-on-startup` | `true` | 启动时执行一次扫描 | T3 |
| `cleanup.interval` | `1h` | 清理周期 | T1/T3 |
| `cleanup.task-ttl` | `24h` | 未完成任务过期时长，0 表示永不清理 | T1 |
| `cleanup.orphan-enabled` | `true` | 启用孤儿数据清理 | T3 |
| `async-merge.enabled` | `false` | 启用异步合并 | T4 |
| `async-merge.thread-pool-size` | `2` | 异步合并线程数 | T4 |
| `metadata-store` | `auto` | 元数据存储类型：auto/memory/file/jdbc/redis | T5 |
| `jdbc.table-name` | `upload_task` | JDBC 存储表名 | T5 |
| `redis.key-prefix` | `upload:task:` | Redis 键前缀 | T5 |
| `redis.ttl-seconds` | `0` | Redis 记录 TTL，0 表示不设 | T5 |

纯 Servlet 场景对应新增 init-param（`UploadFileContext` 解析，命名与上表一致）。

## 五、兼容性说明

- `UploadTask` 新增字段通过 Gson 反序列化缺省为 null，加载时补齐默认值（参照现有 `uploadedChunks` 的处理），旧 JSON 元数据可正常读取；
- `ChunkStorage` 新增 `listIdentifiers()` default 方法返回空集合，现有自定义实现无需改动；
- `merge()` 同步入口保留，异步为可选项，默认关闭，不破坏现有调用方；
- `metadata-store=auto` 完全复刻当前行为（有 `metadata-dir` → file，否则 memory）。

## 六、测试计划

- 单元测试沿用 JUnit 4 + JaCoCo，core 行覆盖率目标不低于当前（88.7%），新增逻辑覆盖：merge 失败回滚、TTL 边界、孤儿清理、异步状态机、JdbcTaskStore CRUD；
- `example/upload-file-demo` 增加 `application.yml` 示例配置并手工验证前端页面上传流程；
- 发布前执行 `mvn verify`（含 gpg 仅在 release profile）与 `mvn jacoco:report` 检查覆盖率。

## 七、文档与示例更新

- `README(.zh-CN).md`：新配置项、新模块、异步合并用法；
- `docs/API(.zh-CN).md`：`mergeAsync` / `mergeStatus` 接口说明；
- `docs/DESIGN(.zh-CN).md`：更新组件职责、目录布局（含临时文件与清理）；
- `docs/ROADMAP(.zh-CN).md`：P0 上线版本标记为 V1.0.0-rc.2；
- 示例 `application.yml` / servlet `web.xml` 补充新配置示例。

## 八、里程碑与发布

1. **M1**：T2 合并原子化（改动最小，先行稳定 merge）；
2. **M2**：T1+T3 清理治理（共享 `StorageCleanupService` 与调度器）；
3. **M3**：T4 合并异步化（依赖 merge 稳定后实施）；
4. **M4**：T5 元数据扩展存储（新模块 + starter 装配，可与 M2/M3 并行）；
5. **M5**：文档/示例/测试补齐，版本 `1.0.0-rc.2 → 1.0.0`，按既有 release profile 发布 Maven Central。
