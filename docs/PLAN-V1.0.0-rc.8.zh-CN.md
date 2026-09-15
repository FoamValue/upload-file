# V1.0.0-rc.8 任务开发计划（GA 前最后收口：配额/锁正确性 + 审计上下文 + 发布工程）

> 🇺🇸 [English](PLAN-V1.0.0-rc.8.md)
>
> rc.7 把 [rc.6 迁移反馈](../doc/user-feedback/upload-file-rc6-migration-feedback.md) 的 P0/P1 全部收口，
> 并让 `metadata-store=redis` 达到「生产可信」。但 rc.7 的使用反馈
> （[upload-file-rc7-usage-feedback.md](../doc/user-feedback/upload-file-rc7-usage-feedback.md)）在**新引入的多实例 /
> 配额能力上**发现了三条正确性缺陷：`RedisQuotaStore` 无自动对账、已合并未确认任务的配额永久泄漏、
> `RedisIdentifierLockProvider` 无续租。同时 rc.6 反馈顺延的**审计上下文**与 **access-log 降噪**仍未处理。
>
> **rc.8 是 `1.0.0` GA 前的最后一个 rc**：只做「正确性收尾 + 审计合规 + GA 发布工程」，不再引入新的大特性；
> 本版发布后进入 **API 冻结**，`1.0.0` 仅做版本号与发布公告。
>
> 反馈来源：`doc/user-feedback/upload-file-rc7-usage-feedback.md`（§5 P1-1/P1-2/P1-3、P2-1/P2-2/P2-3/P2-4、§4-2）；
> 历史反馈 `doc/user-feedback/upload-file-rc6-migration-feedback.md`（§5 P2-1/P2-2）、
> `doc/user-feedback/upload-file-usage-feedback.md`。

## 一、目标与范围

**主题**：把 rc.7 的「多实例 + 原子配额」从「已实现」推进到「已正确」，补齐企业合规所需的审计上下文，
并完成 GA 准入的发布工程（BOM、SOW、二进制兼容门禁）。rc.8 后 API 冻结，不新增对外能力卖点。

| 编号 | 缺口 | 说明 | rc.8 任务 |
| --- | --- | --- | --- |
| G16 | `RedisQuotaStore` 无自动对账 | `reconcile(TaskStore)`（`RedisQuotaStore.java:113`）除测试外**无生产调用**；starter 装配（`UploadFileAutoConfiguration.java:234-246`）只 `create` 不对账，Redis 数据丢失后配额被低估 | T44 |
| G17 | 已合并未确认任务的配额永久泄漏 | `StorageCleanupService.java:241` 对 `isMerged()` 直接 `continue`，`quotaStore.release` 仅在取消/过期删除触发；合并未 confirm 且任务 key TTL 到期后，per-identifier 计数永久计入 `total` | T44 |
| G18 | 分布式锁无续租 | `RedisIdentifierLockProvider.java:73` 固定 `SET NX PX ttl`，`merge()` 全程持锁不续租，长合并超过 `lock.ttl`（默认 30s）即锁失效，破坏跨实例串行化 | T45 |
| G19 | 索引懒迁移非原子 | `RedisTaskStore.ensureIndexMigrated` 的 `SMEMBERS → DEL → ZADD`（`RedisTaskStore.java:92-107`）非原子，多实例迁移或迁移期并发写入会丢条目 | T46 |
| G20 | `list()` 单次 `MGET` 大 N 阻塞 | `RedisTaskStore.list()` 在单连接上一次 `MGET` 全量（`RedisTaskStore.java:173-178`），index 极大时占用连接与 Redis 单线程时间 | T46 |
| G21 | 审计钩子缺请求上下文 | `AccessControlListener.onDecision(identifier, action, decision, elapsedNanos)`（`AccessControlListener.java:28`）无 method/IP/UA，落库审计字段与登录审计不一致 | T47 |
| G22 | `access-log` 按分片输出 | `gate()` 每 chunk 通知监听器，日志量 = 分片数（500MB/5MB = 100 行/文件） | T48 |
| G23 | `TrustedUploadService` 未自动装配 | core 只读门面（rc.7）无 starter Bean，宿主需自建配置类才能走受信读 | T49 |
| G24 | 统一信封缺最小示例 | `UploadErrorRenderer` Bean 已生效（rc.7），但无「宿主 Bean → 统一信封」文档示例 | T50 |
| G25 | 无 BOM | 宿主需手动对齐 7 个产物坐标版本，易漂移 | T51 |
| G26 | 缺 V1.0.0 SOW / API 冻结声明 | rc.3 计划 T13 要求 `docs/PLAN-V1.0.0.md`，当前缺失；GA 无冻结依据 | T52 |
| G27 | 发布工程未 GA 化 | 无二进制兼容门禁、SBOM、可复现构建与 Central 要件清单 | T53 |

## 二、架构与约束

rc.8 **新增 1 个 Maven 产物**（`upload-file-bom`，仅 `dependencyManagement`，无代码）；javax 与 jakarta 两条线
仍以同一 `1.0.0-rc.8` 同步演进，共享逻辑一律留在 `upload-file-core`。

- **additive-first 不变**：新能力一律为新增 SPI / 默认方法 / 新方法 / 新属性；不删除既有成员，不改变既有
  功能默认行为。仅两处受控默认变更（`access-log-scope` 日志量与 `quota.store=redis` 的对账行为），
  见兼容性表并附升级说明。
- **core 不依赖 servlet / 不依赖 Redis**：`QuotaStore.reconcile` 的接口在 core（`default` 空实现），
  Redis 实现与续租在 `upload-file-store-redis`，审计上下文载体在 core，Servlet 层只负责填充。
- **单一事实来源**：配额计数必须可由 `TaskStore.list()` 重建；rc.8 让对账**自动发生**，而非依赖运维手工调用。
- **可回滚**：无磁盘布局 / 任务元数据格式变化；无 Redis 索引结构变化（仅迁移过程原子化）。
- **API 冻结**：rc.8 合并后冻结公开 API 与 `upload-file.*` 属性面；`1.0.0` 不再改代码。

## 三、任务拆解

### T44 配额对账自动化 + 合并未确认任务回收（core + store-redis + starter 两线，G16/G17）

- **涉及文件**：`upload-file-core/.../store/QuotaStore.java`（新增 `default void reconcile(TaskStore)`）、
  `TaskStoreQuotaStore`（空实现，默认等价）、`RedisQuotaStore`（`reconcile` 改 `@Override`）、
  `StorageCleanupService`（孤儿扫描回收配额 + 可选周期性对账）、两条 starter 的 `UploadFileAutoConfiguration`
  （启动期对账）、`README`/`API` 文档。
- **方案**：
  - **SPI 收口**：`QuotaStore` 增加 `default void reconcile(TaskStore taskStore) {}`；默认实现为空
    （`TaskStoreQuotaStore` 本就以任务库为事实来源，无需对账）；`RedisQuotaStore` 覆写现有逻辑。
  - **启动期对账**：starter 在 `QuotaStore` 装配后、`ResumableUploadService` 可用前，调用一次
    `quotaStore.reconcile(taskStore)`（仅当实现非默认、即 `quota.store=redis` 时实际生效），
    纠正 Redis 重启/数据丢失导致的低估与残留 hash 导致的高估。
  - **清理期回收（G17）**：`StorageCleanupService.cleanupOrphans` 在删除无任务记录的 chunk / merged 目录时，
    一并调用 `quotaStore.release(identifier)`；对「已合并未 confirm、任务 key 已 TTL 淘汰」的 identifier，
    其磁盘目录与配额预留同时被回收。`cleanupExpiredTasks` 保持 `isMerged()` 跳过（合并产物由孤儿扫描负责），
    但补一条注释说明配额回收路径。
  - **兜底 TTL（可选）**：`RedisQuotaStore` 的 usage hash 增加与 `cleanup.task-ttl` 对齐的**整表 TTL**
    （或按写入时间打分的 ZSET），作为「对账任务未及时运行」时的最后防线；默认关闭，文档说明。
  - **文档**：明确 `quota.store=redis` 的计数可由 `reconcile` 重建，启动对账默认开启；
    `TaskStoreQuotaStore` 无状态、无漂移。
- **验收**：`quota.store=redis` 下，手动清空 Redis 计数后启动对账可恢复 `usedBytes()` 与任务库一致；
  「merged 未 confirm + 任务过期」后 cleanup 运行一次，`usedBytes()` 归零、无 507 误报；
  默认 `task-store` 路径行为与 rc.7 逐字节一致；两线各测。
- **预估**：1.5 人天。

### T45 `RedisIdentifierLockProvider` 续租 watchdog（store-redis，G18）

- **涉及文件**：`upload-file-store-redis/.../RedisIdentifierLockProvider.java`（及测试）、
  `UploadFileProperties` 的 `lock.*`（新增 `renew-interval`，可选）、`README`/`DESIGN` 文档。
- **方案**：
  - **持有期续租**：`lock()` 返回的 `IdentifierLockHandle` 在持有期间启动一个轻量 watchdog（daemon 线程或
    `ScheduledExecutorService`，按 `ttl/3` 周期），以 Lua「仅当 owner token 仍匹配时 `PEXPIRE`」续租；
    `close()` 时先停 watchdog 再按 owner 校验 `DEL`。
  - **默认与配置**：续租周期默认 `ttl/3`，可经 `upload-file.lock.renew-interval` 覆盖；`ttl` 默认 30s 不变。
    续租失败（key 已易主）时记录 WARN 并放弃续租，让 `acquire-timeout` 语义收敛。
  - **文档量级指引**：`lock.ttl` 必须大于「最坏单次临界区耗时 / 3」；给出 `merge` 大文件场景的推荐值
    （如 500MB 慢盘建议 `ttl >= 120s`），并说明续租使长合并安全。
  - **local 实现不变**：`StripedIdentifierLockProvider` 无需续租（进程内 monitor 不超时）。
- **验收**：构造 `ttl=1s` 的 Redis provider，持锁执行 > `ttl` 的临界区，另一实例在持锁期间无法获取；
  `close()` 后立即可获取；owner 易主后原持有者不误删新锁；单测 + 双实例集成测试（`RedisDockerRule`）。
- **预估**：1.5 人天。

### T46 `RedisTaskStore` 迁移原子化 + `list()` 分批（store-redis，G19/G20）

- **涉及文件**：`upload-file-store-redis/.../RedisTaskStore.java`（及测试）。
- **方案**：
  - **迁移原子化（G19）**：`ensureIndexMigrated` 改为 Lua 脚本，在 Redis 单线程内原子完成
    `TYPE` 判定 → `SMEMBERS` → `ZADD`（score 取任务实际 `updateTime`，缺失用 `now`）→ `DEL`；
    或 `RENAME` 到临时 key 后再迁移，避免 `SMEMBERS → DEL → ZADD` 窗口内的并发 `save()` 丢失。
    迁移结果以哨兵标记（如 `migrated` 字段/独立 key）避免重复迁移。
  - **`list()` 分批（G20）**：`MGET` 按批（默认 500/批，可配常量）执行，或对超大 index 走 `ZSCAN` 流式；
    保持「一次 cleanup 轮次内结果一致」的语义（先 `ZRANGE` 取快照，再分批读取）。
  - 不改变索引结构（仍为 ZSET），不改变返回语义。
- **验收**：并发迁移 + 并发 `save()` 下无 identifier 丢失（多线程断言）；`list()` 在 N=10k 时按批往返
  且返回完整；旧 SET 索引仍可懒迁移；既有 `RedisTaskStoreTest` 全绿。
- **预估**：1 人天。

### T47 审计上下文 `AccessContext`（core + servlet 两线 + starter 两线，G21）

- **涉及文件**：新增 core `AccessContext`（不可变：`method`/`uri`/`remoteAddr`/`userAgent`）与
  `AccessContextHolder`（ThreadLocal，默认空上下文）；`AccessControlListener` 新增 **default** 重载
  `onDecision(AccessContext, identifier, action, decision, elapsedNanos)`（默认桥接到旧 5 参方法，兼容既有实现）；
  core 服务改调新重载；`UploadServlet`/`DownloadServlet`（javax + jakarta）在请求进入时填充
  `AccessContextHolder`、`finally` 清理；starter 的 access-log 监听器消费上下文。
- **方案**：
  - **additive**：旧 5 参 `onDecision` 保留为接口方法（可继续 `implements`），新 6 参方法为 `default`，
    既有宿主 `AccessControlListener`（如 PathFinder 的 `UploadAccessAuditListener`）**零改动可编译**，
    要拿上下文再覆写新方法。
  - **上下文来源**：Servlet 层从 `HttpServletRequest` 取 method/URI/remoteAddr/User-Agent；MVC/纯 core
    调用无上下文时为空对象，字段为 `null`，不抛异常。
  - **审计落库**：`docs/API` 给出「覆写 6 参方法 → 落库 method/uri/ip/ua」的最小示例。
- **验收**：Servlet 路径下监听器收到完整 method/IP/UA；core 手工装配路径收到空上下文不报错；
  既有只实现 5 参方法的监听器编译通过且行为不变；javax/jakarta 两线各测。
- **预估**：1.5 人天。

### T48 `access-log` 降噪（core + servlet 两线 + starter 两线，G22）

- **涉及文件**：`UploadFileProperties.Observability`（新增 `access-log-scope`）、
  `UploadFileAutoConfiguration.uploadFileAccessLogListener`（两线）、
  `UploadFileContext`（纯 Servlet init-param）、`README`/`API` 文档。
- **方案**：
  - 新增 `upload-file.observability.access-log-scope = task | deny | all`：
    - `deny`：仅记录 deny 决策；
    - `task`（**新默认**）：记录 deny + 任务级事件（首个分片、merge/mergeStatus、download、cancel），
      跳过 `action=chunk` 的 allow；
    - `all`：rc.7 行为（每次决策一行）。
  - `access-log=true` 时按 scope 过滤；`access-log=false` 时完全不装配监听器（不变）。
  - 纯 Servlet 路径经 init-param `observability.access-log-scope` 对齐。
- **验收**：`access-log=true` 且默认 scope 下，500MB/5MB 上传仅产生任务级日志（非 100 行）；
  `all` 与 rc.7 逐行一致；`deny` 仅 deny；两线镜像。
- **预估**：0.5 人天。

### T49 `TrustedUploadService` starter 自动装配（starter 两线，G23）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration`、`README`/`API` 文档。
- **方案**：新增 `@Bean @ConditionalOnMissingBean TrustedUploadService`，由 `TaskStore` 构造
  （只读门面，无门控）；Javadoc 与文档明确「**仅供服务端受信流程**，不得暴露到 HTTP 边界」；
  宿主可覆写 Bean。提供 `@ConditionalOnProperty` 显式开关（默认开）以照顾「不希望暴露该 Bean」的宿主。
- **验收**：starter 下可直接注入 `TrustedUploadService`，PathFinder 类宿主可删除自建配置类；
  宿主覆写生效；两线各测。
- **预估**：0.5 人天。

### T50 统一响应信封示例（文档 + demo，G24）

- **涉及文件**：`README(.zh-CN).md`、`docs/API(.zh-CN).md`、`example/upload-file-boot4-demo`。
- **方案**：新增「宿主提供 `UploadErrorRenderer` Bean → 渲染 `ApiResponse{code,message,data}`」最小示例
  （rc.7 已支持宿主 Bean 优先，无需覆盖 Servlet）；demo 增加 `enterprise` profile 下的统一信封配置，
  端到端演示成功体不变、失败体走统一信封。
- **验收**：示例可复制即用；demo 运行验证 `/upload` 失败体为统一信封；文档说明「组件端点成功体仍为裸
  JSON，仅错误体可统一」。
- **预估**：0.5 人天。

### T51 `upload-file-bom` 模块（发布工程，G25）

- **涉及文件**：新增 `upload-file-bom/pom.xml`（`packaging=pom`，`dependencyManagement` 导入 7 个库模块）；
  父 POM `<modules>` 与 `<dependencyManagement>` 纳入 BOM；`README` 快速开始改为 `import` BOM 后只写
  `artifactId`（无版本）。
- **方案**：BOM 覆盖 `upload-file-core`、`upload-file-servlet`、`upload-file-servlet-jakarta`、
  `upload-file-spring-boot-starter`、`upload-file-spring-boot-starter-jakarta`、`upload-file-store-jdbc`、
  `upload-file-store-redis`；版本统一由 `${project.version}` 管理；BOM 自身 `maven.deploy.skip=false`。
- **验收**：宿主仅 `import` BOM + 引入所需 artifact（不写版本）可解析并构建；`mvn deploy` 发布 BOM；
  各模块版本与 BOM 一致。
- **预估**：0.5 人天。

### T52 V1.0.0 SOW + API 冻结声明 + javax 降级策略（文档，G26）

- **涉及文件**：新增 `docs/PLAN-V1.0.0.zh-CN.md` + `docs/PLAN-V1.0.0.md`（SOW，补齐 rc.3 T13 遗留）；
  `README`/`ROADMAP` 发布策略。
- **方案**：SOW 明确：
  - **GA 范围**：以 rc.8 冻结的能力面为准，列明公开 API / SPI / `upload-file.*` 属性；
  - **API 冻结**：rc.8 合并即冻结，`1.0.0` 起遵循语义化版本；`@Deprecated` 项（如
    `ResumableUploadService.getTask(id)`）**保留不删**，计划 2.0 移除；
  - **javax 降级**：`upload-file-servlet` / `upload-file-spring-boot-starter`（javax）自 GA 起标注
    `maintenance / deprecated`，仅安全修复，2.0 收敛到 jakarta 单线；
  - **升级 / 回滚矩阵**：rc.7 → rc.8 → 1.0.0 的配置与行为差异、回滚步骤；
  - **兼容承诺**：无磁盘布局 / 元数据格式变化，无 breaking API。
- **验收**：SOW 评审通过；README 发布策略与冻结声明一致；中英同步。
- **预估**：1 人天。

### T53 发布工程 GA 化 + 文档 / 版本 / 发布（G27）

- **涉及文件**：父 POM（`japicmp-maven-plugin` 或 `revapi`、`cyclonedx-maven-plugin`、
  `project.build.outputTimestamp`）、`.github/workflows`、`CHANGELOG(.zh-CN).md`、`docs/ROADMAP(.zh-CN).md`、
  `docs/DESIGN(.zh-CN).md`、`README(.zh-CN).md`、`docs/API(.zh-CN).md`、各模块 POM
  `1.0.0-rc.7 → 1.0.0-rc.8`、demo。
- **方案**：
  - **二进制兼容门禁**：CI 以 rc.7 为基线运行 japicmp/revapi，任何非 additive 变更使构建失败；
  - **SBOM + 可复现**：接入 CycloneDX SBOM 与 `outputTimestamp`；
  - **Central 要件**：核对 GPG、sources/javadoc jar、`maven.deploy.skip`（rc.7 已修）与
    `-P release` staging 校验；
  - **文档**：CHANGELOG 成文 rc.8 条目（配额/锁正确性 + 审计上下文 + BOM + 冻结）；ROADMAP 标记
    rc.8 为 GA 前最后 rc、登记 GA；DESIGN 补 `AccessContext`、续租、对账；README 顶部加 rc.8 升级提示
    （`access-log-scope` 默认、配额对账）；
  - **发布**：JDK 17+ 全量 `mvn verify` → 版本 `1.0.0-rc.8` → 按 release profile 发布两线 + BOM。
- **验收**：japicmp 对 rc.7 全绿；SBOM 生成；全模块绿；发布公告主打「多实例 + 配额正确性收口、
  审计上下文、GA 就绪」。
- **预估**：1.5 人天。

## 四、新增配置与 SPI 面

| 类别 | 项 | 默认 | 是否 breaking |
| --- | --- | --- | --- |
| SPI | `QuotaStore.reconcile(TaskStore)`（新增 `default`） | 空实现（`TaskStoreQuotaStore`） | 否 |
| 属性 | `upload-file.lock.renew-interval` | `ttl/3` | 否 |
| 属性 | `upload-file.observability.access-log-scope`（`task`/`deny`/`all`） | `task` | **是**（日志量；`all` 恢复旧行为） |
| 属性 | `upload-file.quota.store=redis` 的行为 | 启动自动对账 | 否（修正低估/高估） |
| API | `AccessContext` / `AccessContextHolder`（新增） | 空上下文 | 否 |
| API | `AccessControlListener.onDecision(AccessContext, ...)`（新增 `default` 重载） | 桥接旧 5 参方法 | 否 |
| API | `TrustedUploadService` starter Bean（新增，`@ConditionalOnMissingBean`） | 自动装配 | 否 |
| 产物 | `upload-file-bom`（新增，`packaging=pom`） | — | 否 |

## 五、兼容性（rc.7 → rc.8）

| 行为 | rc.7 | rc.8 | 说明 |
| --- | --- | --- | --- |
| `QuotaStore`（`task-store` 默认） | 近似、以任务库为准 | 行为不变 | `reconcile` 默认空实现 |
| `quota.store=redis` | 计数可能漂移、合并未确认泄漏 | 启动自动对账 + 清理回收 | 修正低估/高估与泄漏 |
| `RedisIdentifierLockProvider` | 固定 TTL、不续租 | watchdog 续租（`ttl/3`） | 长合并不再锁失效；`local` 不变 |
| `RedisTaskStore` 索引迁移 | 非原子 `SMEMBERS→DEL→ZADD` | Lua/`RENAME` 原子迁移 | 多实例迁移不丢条目 |
| `RedisTaskStore.list()` | 单次 `MGET` | 分批 `MGET`/`ZSCAN` | 结果等价、大 N 更稳 |
| 审计监听 | 5 参 `onDecision`（无请求上下文） | 新增 6 参 `default` 重载 | 增量；旧实现零改动 |
| `access-log` 输出 | 每决策一行 | 默认 `task` 级；`all` 恢复 | **breaking-default**（仅日志量） |
| `TrustedUploadService` | 宿主自建 | starter `@ConditionalOnMissingBean` | 增量；可覆写/关闭 |
| 成功体 / 端点 / 磁盘与元数据格式 / 既有属性键 | — | 不变 | — |

- 新增 `upload-file-bom` 产物，其余产物坐标与既有 `upload-file.*` 键不变；core 手工装配继续可用。
- 无磁盘布局 / 任务元数据格式变化；无 Redis 索引结构变化。

## 六、测试计划

- store-redis：配额对账恢复、合并未确认回收（cleanup 后 `usedBytes()==0`）、续租长临界区互斥
  （`RedisDockerRule` 双实例）、迁移原子性并发无丢条目、`list()` 分批 N=10k 完整性。
- core：`QuotaStore.reconcile` 默认空实现等价、`AccessContext`/`AccessContextHolder` 空上下文、
  `AccessControlListener` 6 参 `default` 桥接、`cleanupOrphans` 配额回收。
- starter（javax + jakarta，互为镜像）：启动对账调用、`access-log-scope` 三值、`TrustedUploadService`
  装配与覆写、`UploadErrorRenderer` 统一信封示例。
- 兼容回归：默认路径对 rc.7 逐字节断言不变；`access-log-scope=all` 与 rc.7 逐行一致。
- 发布：japicmp 对 rc.7 基线全绿；JDK 17+ 全 reactor `mvn verify`；各模块保留 JaCoCo。

## 七、文档与示例更新

- `README(.zh-CN).md`：rc.8 升级提示（`access-log-scope` 默认、`quota.store=redis` 自动对账）、
  BOM 快速开始、`TrustedUploadService` 自动装配、统一信封示例、GA 冻结与 javax 降级说明。
- `docs/API(.zh-CN).md`：`AccessContext` 与 6 参监听器、`access-log-scope`、`QuotaStore.reconcile`、
  统一信封渲染优先级。
- `docs/DESIGN(.zh-CN).md`：锁续租、配额对账与回收、索引原子迁移、审计上下文透传。
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`：rc.8 计划条目 → 发布后翻转；GA 登记；
  P2-5 生态项进入 GA 后 1.0.x/1.1。
- 新增 `docs/PLAN-V1.0.0(.zh-CN).md`（SOW）。

## 八、里程碑与发布

1. **M1**（T44、T45、T46）：配额 / 锁 / 存储正确性收口——最高优先，GA 硬门槛；
2. **M2**（T47、T48）：审计上下文 + access-log 降噪——企业合规；
3. **M3**（T49、T50）：`TrustedUploadService` 自动装配 + 统一信封示例——集成体验；
4. **M4**（T51、T52）：BOM + SOW / API 冻结——GA 发布工程；
5. **M5**（T53）：二进制兼容门禁 / SBOM / 版本 `1.0.0-rc.7 → 1.0.0-rc.8`、全量 `mvn verify`、
   CHANGELOG/ROADMAP 同步并发布；
6. **M6（GA）**：rc.8 冻结后版本 `1.0.0-rc.8 → 1.0.0`，仅版本号与发布公告，不改代码。

## 九、本版不纳入（GA 后 1.0.x / 1.1）

- **生态项（ROADMAP P2-5）**：Prometheus 指标、上传完成 Webhook、内容寻址秒传、整文件 SHA-256 校验、
  对象存储后端、tus 协议、病毒扫描钩子——按 ROADMAP 节奏推进。
- **单用户配额 / 限速、多租户命名空间、回收站 / 版本管理、预签名下载**——GA 后按需排期。
- **javax 线收敛**：自 GA 起维护态，2.0 移除（T52 已声明策略）。

## 十、GA 准入判据

rc.8 合并后，`1.0.0` 可发布当且仅当：

1. rc.7 反馈的 **P1-1 / P1-2 / P1-3 全部闭环**（T44/T45）且 P2 项按上表处理；
2. rc.6 反馈顺延的 **P2-1 / P2-2 闭环**（T47/T48）；
3. `docs/PLAN-V1.0.0` SOW 评审通过，**API 与属性面冻结**（T52）；
4. japicmp 对 rc.7 基线全绿，BOM 发布可用（T51/T53）；
5. JDK 17+ 全 reactor `mvn verify` 全绿。
