# V1.0.0-rc.7 任务开发计划（存储正确性与扩展点一致性收口）

> 🇺🇸 [English](PLAN-V1.0.0-rc.7.md)
>
> rc.6 让 path-finder 用官方 HTTP 层替换了 226 行受控镜像代码（见
> [rc.6 迁移反馈](../doc/user-feedback/upload-file-rc6-migration-feedback.md)），但该反馈同时暴露了
> **`metadata-store=redis` 生产环境的真实隐患**（索引无限泄漏、`list()` N+1）、**文档承诺与实现不一致**
> （starter 不消费宿主 `UploadErrorRenderer` Bean）、**安全默认值 footgun**（multipart 无上限），以及
> **多实例串行化/配额原子性缺失**与**受信 API 误用无编译期保护**。rc.7 的目标是把 rc.6 的「可接入」推进到
> 「生产可信」：修存储正确性、补齐扩展点一致性、给多实例与安全默认兜底。
>
> ✅ **状态：已在 `1.0.0-rc.7` 实现并发布。** T35–T43 全部完成：Redis 索引治理 + 批量读取、starter 消费
> `UploadErrorRenderer` Bean、multipart 安全默认、分布式 `IdentifierLockProvider`、原子 `QuotaStore`、
> 受信读 API 收口、`AbstractAccessControl`、starter 装配与安全告警——见 [更新日志](../CHANGELOG.zh-CN.md)。
>
> 反馈来源：`doc/user-feedback/upload-file-rc6-migration-feedback.md`（§5 P0-1/P0-2/P0-3/P0-4、P1-1/P1-2/P1-3/P1-4、
> P2-3、§6 遗留）；历史反馈 `doc/user-feedback/upload-file-usage-feedback.md`。

## 一、目标与范围

**主题**：以「正确性 / 资源安全 / 扩展点一致性」为唯一目标，不引入新的大特性；把 rc.6 反馈中标记为
P0 与 P1 的缺口全部收口，P2 的可观测与生态项顺延 rc.8。

| 编号 | 缺口 | 说明 | rc.7 任务 |
| --- | --- | --- | --- |
| G7 | `RedisTaskStore` index 集合无限泄漏 | `sadd` 后 index 无 TTL、成员从不清理，`ttl-seconds` 过期后 `list()` 遇 `null` 仅 `continue` 不 `SREM` | T35 |
| G8 | `RedisTaskStore.list()` N+1 | `smembers` 后逐 identifier `GET`，清理每轮放大 RTT | T35 |
| G9 | starter 不消费宿主 `UploadErrorRenderer` Bean | 文档承诺「提供 Bean 即生效」，实现却直接 `UploadErrorRenderers.from(property)`，SPI 在 starter 路径失效 | T36 |
| G10 | `multipart.strategy=component` 默认无上限 | `max-chunk-size`/`max-request-size` 默认 `-1`，宿主只设服务层 `max-file-size` 时容器层无限，构成 DoS 面 | T37 |
| G11 | `IdentifierLock` 仅进程内 | 水平扩展时同 identifier 的上传/合并不互斥，仅清理有 `RedisCleanupLock` | T38 |
| G12 | 全局配额 `setMaxTotalBytes` 非原子 | check-then-act 竞态，并发上传可超 `quota.max-bytes` | T39 |
| G13 | 受信读 API 误用无编译期保护 | `getProgress(id)` / `isChunkUploaded(id,i)` / `getTask(id)` 公开且不做鉴权，仅 Javadoc 警示 | T40 |
| G14 | `AccessControl` 契约易误用 | `decide()` 桥接 `check()`，而 `check()` 默认抛 `UnsupportedOperationException`：两者都不覆写时**编译通过、运行期才炸** | T41 |
| G15 | starter 装配冗余 + 无安全告警 | `/download` 关闭时仍无条件建 `ResumableDownloadService`；`security.enabled=false` 且无宿主 `AccessControl` 时端点裸奔无告警 | T42 |

## 二、架构与约束

rc.7 **不新增 Maven 产物**；javax 与 jakarta 两条线以同一 `1.0.0-rc.7` 同步演进，共享逻辑一律留在
`upload-file-core`（新 SPI 的可选实现放对应 store 模块）。

- **additive-first 不变**：新能力一律为新增 SPI / 新方法 / 新属性；不删除既有成员，不改变既有默认行为，
  除非该默认是明确的安全缺陷（G10 的「无上限」记为 breaking-default，见兼容性表）。
- **core 不依赖 servlet / 不依赖 Redis**：`IdentifierLockProvider`、`QuotaStore` 的接口在 core，
  Redis 实现放 `upload-file-store-redis`，由 starter 按 `@ConditionalOnClass` + 属性选装，缺失即回退
  （延续 `metadata-store` 的探测范式）。
- **单一事实来源**：存储元数据（Redis 索引结构、配额计数）必须可由 `TaskStore.list()` 重建/对账，
  避免引入第二份会漂移的真相。
- **可回滚**：无磁盘布局 / 任务元数据格式变化；Redis 索引由 SET → ZSET 为**一次性懒迁移**（读时兼容旧集合）。
- 不删除任何 `upload-file.*` 属性；既有属性默认值除 G10 外不变。

## 三、任务拆解

### T35 `RedisTaskStore` 索引治理 + 批量读取（store-redis，G7/G8）

- **涉及文件**：`upload-file-store-redis/.../RedisTaskStore.java`（及测试）。
- **方案**：
  - **索引由 SET 改为 ZSET**：`save()` 用 `ZADD indexKey <updateTimeMillis> identifier`（score 恒为
    `updateTime`）；`remove()` 用 `ZREM`。
  - **懒迁移**：构造/首次使用时若 `TYPE indexKey == set`（旧 rc.6 数据），读取 `SMEMBERS` → `ZADD`
    （score 用各任务实际 `updateTime`，读不到则用 `now`）→ `DEL` 旧集合，一次性完成。
  - **`list()` 批量化 + 修剪**：`ZRANGE indexKey 0 -1` 取 identifier，用 **pipeline `MGET`** 一次取回；
    `ttlSeconds > 0` 时先 `ZREMRANGEBYSCORE indexKey 0 (now - ttlMillis)` 删除确定过期的索引；对 `MGET`
    返回 `null`（key 已过期）的 identifier 执行 `ZREM`（惰性修剪）。返回剩余有效任务。
  - 新增包内 `pruneIndex()` 供清理/诊断显式调用；`listIdentifiers()` 覆盖同源。
- **验收**：`ttl-seconds=1` 下写入 N 条、等待过期后 `list()` 返回空且 `ZCARD indexKey == 0`（泄漏消除）；
  `list()` 对 N 条任务仅 1 次 pipeline 往返（可用 `Jedis` mock/`MONITOR` 断言或计数断言）；旧 SET 索引数据
  可被迁移读取；既有 `RedisTaskStoreTest` 全绿。
- **预估**：1.5 人天。

### T36 starter 消费宿主 `UploadErrorRenderer` Bean（starter 两线，G9）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration`（`uploadFileServlet` / `downloadFileServlet` Bean）、
  `README(.zh-CN).md` / `docs/API(.zh-CN).md` / `CHANGELOG(.zh-CN).md`。
- **方案**：两个 servlet Bean 增加 `ObjectProvider<UploadErrorRenderer>` 参数；
  `servlet.setErrorRenderer(provider.getIfAvailable(() -> UploadErrorRenderers.from(properties.getHttp().getErrorBody())))`
  ——**宿主 Bean 优先，缺省回落 `legacy`/`standard`**。纯 Servlet 路径已支持 `setErrorRenderer`，无需改动。
  文档明确「starter 路径下提供 `UploadErrorRenderer` Bean 即生效」。
- **验收**：宿主提供 renderer Bean 时两个端点失败体走自定义信封；未提供时行为与 rc.6 逐字节一致；
  javax/jakarta 两线各测；README 示例可复制即用。
- **预估**：0.5 人天。

### T37 multipart 安全默认（starter 两线，G10）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration.multipartConfig(...)`、`UploadFileProperties`、
  配置表文档。
- **方案**：`component` 策略下，当 `max-request-size <= 0`（未显式设置）时按以下顺序推导**有界**的容器上限：
  `max-chunk-size > 0 ? max-chunk-size + MULTIPART_OVERHEAD : (max-file-size > 0 ? max-file-size + MULTIPART_OVERHEAD : -1)`
  （`MULTIPART_OVERHEAD` 取 1MB，覆盖 multipart 边界/头部）；推导发生时记一条 INFO；若推导后仍为 `-1`
  （三值皆未设）则记 **WARN「容器层 multipart 无上限，存在 DoS 面」**。显式设置 `max-request-size` 者行为不变。
  在配置表为 `max-chunk-size`/`max-request-size` 补充安全默认说明。
- **验收**：只设 `max-file-size` 时容器上限被推导为有界值；三值皆未设时输出 WARN；显式设置时逐字节不变；
  两线各测。
- **预估**：0.5 人天。

### T38 `IdentifierLockProvider` SPI + Redis 分布式串行化（core + store-redis + starter 两线，G11）

- **涉及文件**：新增 core SPI `IdentifierLockProvider` / `IdentifierLockHandle` / `StripedIdentifierLockProvider`；
  保留现有 `IdentifierLock`（进程内分片锁）作为 `local` 实现底座；`ResumableUploadService` /
  `StorageCleanupService` 改为经 provider 获取临界区；`upload-file-store-redis` 新增
  `RedisIdentifierLockProvider`；两条 starter 增加选装与属性。
- **方案**：
  - **SPI（additive）**：`IdentifierLockProvider#lock(String identifier)` 返回 `AutoCloseable`
    的 `IdentifierLockHandle`；服务把现有 `synchronized (lockFor(id)) { ... }` 改为
    `try (IdentifierLockHandle h = identifierLockProvider.lock(id)) { ... }`。
  - **默认不变**：`local` provider 内部包裹现有 `IdentifierLock`（同一 `Object` monitor），
    既有构造器/行为完全不变；`StorageCleanupService` 与上传服务继续共享同一 provider 实例。
  - **Redis 实现**：`RedisIdentifierLockProvider` 以 `SET key owner NX PX ttl` 抢占、带 owner 校验的
    `DEL` 释放、可配 `acquire-timeout`/`ttl`（默认分别为 10s / 30s），支持等待重试；key 前缀沿用
    `key-prefix`（默认 `upload:lock:`）。
  - **选装**：新增 `upload-file.lock.identifier-lock=local|redis`（默认 `local`）+
    `upload-file.lock.acquire-timeout` / `upload-file.lock.ttl`；starter 在
    `@ConditionalOnClass(RedisIdentifierLockProvider)` + `identifier-lock=redis` 时装配，
    未选/类缺失回退 `local` 并 INFO。
  - **文档边界**：分布式锁仅在「多实例共享同一磁盘/对象存储」前提下保证互斥；锁 TTL 到期视为持有者异常，
    需 `acquire-timeout` 收敛重试。
- **验收**：`local` 下既有并发/串行测试全绿且零行为变化；Redis 下两个服务实例对同 identifier 的上传/合并
  被串行化（集成测试并发断言）；锁超时与 owner 释放有单测；多实例文档成文。
- **预估**：2.5 人天。

### T39 `QuotaStore` SPI + 原子配额（core + store-redis + starter 两线，G12）

- **涉及文件**：新增 core SPI `QuotaStore`（`long usedBytes()` / `boolean tryReserve(String identifier, long bytes)` /
  `void release(String identifier, long bytes)`）+ 默认 `TaskStoreQuotaStore`（复用现有 `taskStore.list()`
  近似算法，行为等价）；`ResumableUploadService` 配额路径改经 `QuotaStore`；`upload-file-store-redis` 新增
  `RedisQuotaStore`（Lua 原子 check-and-incr）；两条 starter 选装。
- **方案**：
  - **默认等价**：未配置时使用 `TaskStoreQuotaStore`，`checkQuota` 的语义与现有实现一致（近似、无锁）。
  - **原子实现**：`RedisQuotaStore` 用一段 Lua 脚本在 Redis 侧原子完成「读计数 + 判上限 + `INCRBY`」，
    消除 check-then-act 竞态；`release` 用 `DECRBY`（下界 0）。
  - **对账**：提供 `reconcile()` 以 `TaskStore.list()` 重算计数（启动/清理时调用），避免计数器漂移；
    这是「单一事实来源」约束的落地。
  - **选装**：新增 `upload-file.quota.store=task-store|redis`（默认 `task-store`）；starter 在
    `@ConditionalOnClass(RedisQuotaStore)` + `quota.store=redis` 时装配，缺失回退 `task-store` 并 WARN。
- **验收**：默认路径与 rc.6 配额用例逐字节一致；Redis 路径在并发上传下不超限（并发测试断言）；
  `reconcile()` 可从任务库纠正漂移计数；两线选装测试。
- **预估**：2 人天。

### T40 受信读 API 收口（core，G13）

- **涉及文件**：新增 `TrustedUploadService`（只读受信门面：`getTask` / `getProgress` / `isChunkUploaded`
  的无门控变体）；`ResumableUploadService` 三个无 token 读方法标 `@Deprecated` 并指向替代；
  confirm 阶段文档与示例迁移到 `TrustedUploadService`。
- **方案**：不删除任何方法（受信 confirm 流程仍合法），通过 **命名 + 类型** 让越权误用在编译期可见：
  无门控读只经 `TrustedUploadService` 暴露（`ResumableUploadService.getTaskTrusted(...)` 等受信别名），
  HTTP 边界一律走 rc.6 的带 token 门控重载；`@Deprecated` 的旧名保留可编译运行。
- **验收**：`TrustedUploadService` 单测；旧方法仍可编译（仅告警）；confirm 文档改用受信门面；
  servlet/starter 路径不受影响。
- **预估**：0.5 人天。

### T41 `AccessControl` 契约收敛（core，G14）

- **涉及文件**：新增抽象基类 `AbstractAccessControl`（`decide()` 抽象，`check()` 提供桥接）与静态工厂
  `AccessControl.ofDecide(...)` / `AccessControl.ofCheck(...)`；`AccessControl` Javadoc 明确「必须覆写其一」。
- **方案**：不改变接口默认方法语义（保持 `check()` 抛 `UnsupportedOperationException` 以暴露误用），
  而是**提供正确的实现基座**：继承 `AbstractAccessControl` 者只实现 `decide()` 即编译期强制；
  函数式诉求经 `ofDecide` 恢复 lambda 能力（不再依赖 `AccessControl` 是函数式接口）。
- **验收**：`AbstractAccessControl` 子类不实现 `decide()` 编译失败；`ofDecide`/`ofCheck` 桥接单测；
  README 迁移片段更新为「继承 `AbstractAccessControl` 覆写 `decide()`」。
- **预估**：0.5 人天。

### T42 starter 装配优化 + 安全默认告警（starter 两线，G15）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration`。
- **方案**：
  - `resumableDownloadService` Bean 加 `@Lazy`，使 `/download` 关闭时不再于启动期构建（宿主显式注入时
    仍可按需创建，零破坏）；
  - 启动期安全校验：当 `endpoint.enabled=true` 且 `security.enabled=false` 且容器内不存在宿主
    `AccessControl` Bean（即仍为 `PermitAllAccessControl`）时，输出 **WARN「上传端点无访问控制」**；
    当 `security.enabled=true` 但 token 为空时保持 rc.3 的 fail-fast。
- **验收**：`/download` 关闭时无 `ResumableDownloadService` 实例化（可断言 Bean 延迟）；两种安全组合的
  告警/fail-fast 各测；两线镜像。
- **预估**：0.5 人天。

### T43 更新日志 / 路线图 / 文档 / 发布（G7–G15 同步 + 发布）

- **涉及文件**：`CHANGELOG(.zh-CN).md`、`docs/ROADMAP(.zh-CN).md`、`docs/DESIGN(.zh-CN).md`、
  `README(.zh-CN).md`、`docs/API(.zh-CN).md`、`docs/PLAN-*` 状态翻转、父 POM 与各模块 POM
  `1.0.0-rc.6 → 1.0.0-rc.7`、demo。
- **方案**：成文 rc.7 发布条目（P0/P1 修复清单 + 新增 SPI/属性）；README 顶部加 rc.7 升级提示
  （multipart 安全默认、Redis 索引迁移）；API/DESIGN 补 `IdentifierLockProvider` / `QuotaStore` /
  `TrustedUploadService` / `AbstractAccessControl`；全部测试通过后翻转 PLAN 状态；JDK 17+ 全量 `mvn verify`；
  按既有 release profile 发布两线。
- **验收**：CHANGELOG 逐项列出修复与新增；全模块绿；发布公告主打「`metadata-store=redis` 生产可信 +
  多实例串行化 + 扩展点一致性」。
- **预估**：1 人天。

## 四、新增配置与 SPI 面

| 类别 | 项 | 默认 | 是否 breaking |
| --- | --- | --- | --- |
| 属性 | `upload-file.lock.identifier-lock`（`local`/`redis`） | `local` | 否 |
| 属性 | `upload-file.lock.acquire-timeout` | `10s` | 否 |
| 属性 | `upload-file.lock.ttl` | `30s` | 否 |
| 属性 | `upload-file.quota.store`（`task-store`/`redis`） | `task-store` | 否 |
| 属性 | `multipart.strategy=component` 的请求上限**自动推导** | 有界（见 T37） | **是**（原为无上限） |
| SPI | `IdentifierLockProvider` / `IdentifierLockHandle`（新增） | `StripedIdentifierLockProvider`（= rc.6 行为） | 否 |
| SPI | `QuotaStore`（新增） | `TaskStoreQuotaStore`（= rc.6 行为） | 否 |
| API | `TrustedUploadService`、`ResumableUploadService.*Trusted`（新增） | 无 | 否 |
| API | `AbstractAccessControl`、`AccessControl.ofDecide/ofCheck`（新增） | 无 | 否 |
| 存储 | `RedisTaskStore` 索引 SET → ZSET | 读时懒迁移 | 否（旧数据可读） |

## 五、兼容性（rc.6 → rc.7）

| 行为 | rc.6 | rc.7 | 说明 |
| --- | --- | --- | --- |
| `RedisTaskStore` 索引 | SET，无界 | ZSET，按 `updateTime` 修剪 | 修复泄漏；旧数据一次性懒迁移 |
| `RedisTaskStore.list()` | N+1 `GET` | pipeline `MGET` | 行为等价、性能改善 |
| starter 失败体渲染 | 仅 `legacy`/`standard` | 宿主 `UploadErrorRenderer` Bean 优先 | 增量；无 Bean 时不变 |
| multipart `component` 请求上限 | 未设 = 无上限 | 未设 = 按 chunk/file 推导有界 | **breaking-default**；显式设置者不变；附 WARN |
| 多实例上传/合并 | 进程内锁 | `identifier-lock=redis` 可跨实例串行 | 增量；默认 `local` 不变 |
| 全局配额 | 近似、非原子 | 默认不变；`quota.store=redis` 原子 | 增量 |
| 受信读 | 公开无门控 | 无门控收口到 `TrustedUploadService`；旧名 `@Deprecated` | 增量；旧调用可编译 |
| `AccessControl` 实现 | 接口默认方法 | 新增 `AbstractAccessControl` 基座 | 增量；接口语义不变 |
| 成功体 / 端点 / 既有 `upload-file.*` 键 / 磁盘与元数据格式 | — | 不变 | — |

- 不新增产物；core 手工装配继续可用，且在不选用新 SPI 时不受影响。
- 无磁盘布局 / 任务元数据格式变化；Redis 索引迁移在首次读写时自动完成，回滚 rc.6 前建议保留旧索引兼容窗口。

## 六、测试计划

- store-redis：索引泄漏（TTL 过期后 `ZCARD==0`）、`list()` 批量往返、旧 SET 索引懒迁移、`RedisQuotaStore`
  并发不超限、`RedisIdentifierLockProvider` 双实例互斥（沿用 `RedisDockerRule`）。
- core：`QuotaStore` 默认等价与 `reconcile()`、`IdentifierLockProvider` 默认等价、`TrustedUploadService`、
  `AbstractAccessControl` / `ofDecide` / `ofCheck` 桥接。
- starter（javax + jakarta，互为镜像）：`UploadErrorRenderer` Bean 优先、multipart 推导与 WARN、`@Lazy`
  download service、安全默认告警、新属性选装与回退。
- 兼容回归：默认路径对 rc.6 逐字节断言不变；`multipart` 显式设置路径不变。
- JDK 17+ 全 reactor `mvn verify`；各模块保留 JaCoCo。

## 七、文档与示例更新

- `README(.zh-CN).md`：rc.7 升级提示（multipart 安全默认、Redis 索引迁移）、`UploadErrorRenderer` Bean 生效说明、
  多实例串行化与配额原子化配置、`AbstractAccessControl` 迁移片段。
- `docs/API(.zh-CN).md`：失败体渲染优先级（宿主 Bean > `legacy`/`standard`）。
- `docs/DESIGN(.zh-CN).md`：`IdentifierLockProvider`、`QuotaStore`、`TrustedUploadService`、`AbstractAccessControl`。
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`：rc.7 计划条目 → 发布后翻转；P2-4 生态项与
  P2-1/P2-2 顺延 rc.8。

## 八、里程碑与发布

1. **M1**（T35、T36、T37）：存储正确性 + 扩展点一致性 + 安全默认——最高优先，独立可发布；
2. **M2**（T38）：多实例 identifier 串行化（SPI + Redis 实现）；
3. **M3**（T39）：原子配额（SPI + Redis 实现）；
4. **M4**（T40、T41）：受信 API 收口 + `AccessControl` 基座；
5. **M5**（T42）：starter 装配优化 + 安全告警；
6. **M6**（T43）：版本 `1.0.0-rc.6 → 1.0.0-rc.7`、JDK 17+ 全量 `mvn verify`、CHANGELOG/ROADMAP 同步并发布。

## 九、本版不纳入（顺延 rc.8）

- **P2-1 审计上下文**：`AccessControlListener` 携带 method/IP/UA（需 core↔servlet 轻量上下文透传，独立设计）。
- **P2-2 access-log 降噪**：按 identifier 聚合/采样、仅 deny 与任务级事件（与 P2-1 同域，合并设计）。
- **P2-4 生态项**：Prometheus 指标、上传完成 Webhook、内容寻址秒传、整文件 SHA-256 校验、对象存储后端、
  tus 协议、病毒扫描钩子（在 `ChunkStorage` / 合并完成事件预留扩展点）——按 ROADMAP 节奏推进。
