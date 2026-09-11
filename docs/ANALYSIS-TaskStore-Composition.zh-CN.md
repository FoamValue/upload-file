# TaskStore 组合能力调研：Spring AI Advisor 机制是否适用

> 🇺🇸 [English](ANALYSIS-TaskStore-Composition.md)

> 状态：**调研完成，决定搁置（deferred），暂不实现。** 本文仅作记录，供未来重新评估。
> 触发问题：能否借鉴 Spring AI 的 Advisor 层机制，把 `upload-file-store-redis` 与
> `upload-file-store-jdbc` 合并。

## 1. 动机

当前 `-store-redis` 与 `-store-jdbc` 是两个并列、互斥、单选的可选模块，运行时只能二选一，无法叠加
（例如「Redis 做读缓存 + JDBC 做持久层」）。曾设想：是否存在一种类似 Spring AI Advisor 的
「拦截链」机制，可以统一/组合这两个存储后端。

## 2. Spring AI Advisor 机制速览

Advisor 是 `ChatClient` 调用上的一条**有序环绕（around）拦截链**：

- 核心接口：`CallAdvisor` / `CallAdvisorChain`（同步）、`StreamAdvisor` / `StreamAdvisorChain`（流式）；
  关键方法 `adviseCall(req, chain)` / `adviseStream(...)`。
- 每个 Advisor 调用 `chain.nextCall(req)` 把请求交给链上的下一个；可在 `next` **之前**改请求、
  **之后**改响应，也可**不调用 next 直接短路**并自行填充响应。
- `getOrder()` 排序：值越小越先处理请求、越后处理响应（栈式，即 AOP around advice）。
- `ChatClientRequest` / `ChatClientResponse` 携带共享的 `advise-context`，用于跨 Advisor 传状态。
- 链尾由框架自动补一个「真正发请求给模型」的终端节点。

一句话：**Advisor = 责任链 + 装饰器 + 共享上下文**，用于拦截/增强一次调用，而非用于打包或合并模块。

## 3. 当前组件是否已应用

**否。** 全仓库生产代码中没有任何 `Interceptor / Filter / Chain / Composite / Advisor / Decorator`
实现。现有扩展点均为「单实现替换」或「观察者」，不是链式包裹：

| 扩展点 | 位置 | 形态 |
| --- | --- | --- |
| `TaskStore` | `upload-file-core/.../core/store/TaskStore.java:21` | SPI（`get/save/remove/list`） |
| `ChunkStorage` | `.../core/storage/ChunkStorage.java:20` | SPI |
| `CleanupLock` | `.../core/util/CleanupLock.java:17` | SPI |
| `AccessControl` | `.../core/security/AccessControl.java:34` | SPI（决策） |
| `AccessControlListener` | `.../core/security/AccessControlListener.java:18` | 广播式监听器 |
| `UploadErrorRenderer` | `.../core/error/UploadErrorRenderer.java:18` | SPI（渲染） |
| 各自动配置 Bean | starter `UploadFileAutoConfiguration` | `@ConditionalOnMissingBean` 覆盖 |

`AccessControlListener` 最接近「链」，但它是**广播式观察者**（`CopyOnWriteArrayList`，只通知、不包裹、
不短路），与 Advisor 的 around 链语义不同。

## 4. `-redis` / `-jdbc` 现状

两者并列、互斥、单选，由配置在启动时选定一个：

- `RedisTaskStore`：`upload-file-store-redis/.../RedisTaskStore.java:31`
- `JdbcTaskStore`：`upload-file-store-jdbc/.../JdbcTaskStore.java:32`
- 选择逻辑：`metadata-store=memory|file|jdbc|redis|auto`，见
  `UploadFileAutoConfiguration.uploadFileTaskStore()`（javax `:109-153`，jakarta 同构）。
- `jdbc/redis` 通过**类名反射探测**（`JDBC_STORE_CLASS` / `REDIS_STORE_CLASS`）；模块或
  `DataSource` 缺失则告警并回退 `auto`（有 `metadata-dir` → `FileTaskStore`，否则 `MemoryTaskStore`）。
- 两个 store 模块在 starter 中均为 `<optional>true</optional>`，**刻意不传递引入**，避免把
  Jedis / JDBC 驱动强加给所有使用者。

**结论：当前无法叠加，只装配一个 `TaskStore` Bean。**

## 5. 「合并」的三种解读与评估

| 解读 | 是否适用 Advisor 思路 | 评估 |
| --- | --- | --- |
| A. 打包合并为单一 artifact | 否 | 属于构建/依赖问题，与 Advisor 无关；合并会把 Jedis + JDBC 依赖塞给所有使用者，破坏现有 optional 设计。**不推荐。** |
| B. 运行时组合（Redis L1 缓存 + JDBC L2 持久层 / 双写） | 是（责任链类比） | 唯一真正对得上「链式包裹」的场景。可新增 `CompositeTaskStore`（装饰链，末端接真实后端）。**有价值，但需先定语义。** |
| C. 统一抽象/命名 | 否 | 接口已是 `TaskStore`，仅归并模块名收益有限。 |

## 6. 若未来实现方案 B：设计要点与语义难点

Advisor 类比只能提供「链」的形状，不能自动解决存储语义，需先明确：

- `list()`：缓存 + DB 链下是「合并去重」还是「只查权威源（DB）」？
- `save()`：write-through（同步双写）还是 write-behind（异步刷盘）？失败如何补偿？
- `remove()`：删除顺序、缓存失效与 DB 删除的一致性。
- `get()` 未命中是否回填缓存、TTL 策略，以及与 `TaskStoreMigrator` / `UploadTask.schemaVersion`
  的交互。
- 多实例下的并发与缓存一致性（当前已有 `IdentifierLock`、`CleanupLock` 可复用）。
- 链末端必须存在一个权威（authoritative）store；装饰节点不得改变 `get/save/remove/list` 的
  失败语义（`TaskStore` 无显式异常契约，建议统一为运行时异常并文档化）。

## 7. 决策与重新评估的触发条件

- **决策**：搁置，不实现；保持 `-redis` / `-jdbc` 并列可选模块的现状。
- **重新评估的触发条件**（满足其一）：
  1. 出现明确的「缓存 + 持久化」多级存储需求（性能或成本驱动）；
  2. 接入方反馈希望在同一部署中同时利用 Redis 与关系库；
  3. 决定将 store 模块收敛为可组合插件体系（届时再评估命名与坐标）。

## 8. 参考

- Spring AI Advisors API：<https://docs.spring.io/spring-ai/reference/api/advisors.html>
- 相关设计文档：[DESIGN.zh-CN.md](DESIGN.zh-CN.md)（模块依赖与组件职责）
