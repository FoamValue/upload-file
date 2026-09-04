# V1.0.0-rc.4 任务开发计划（反馈驱动的集成优化）

> 🇺🇸 [English](PLAN-V1.0.0-rc.4.md)
>
> rc.3 闭环了「生产就绪」缺口，但尚未被本仓库之外真实消费。rc.4 是**首个由真实集成反馈驱动的版本**
> （PathFinder 文件管理系统，`doc/user-feedback/upload-file-usage-feedback.md`，参考 commit `62ae062`）：
> 针对单测无法预见的 P0/P1 集成痛点——confirm 阶段稳定的「按 identifier 读 / 取消」契约、core 异常稳定的
> `UploadErrorCode` HTTP 语义，以及把清理 / 手工（core）装配责任讲清楚的文档。
>
> ✅ **状态：T14–T18 全部实现并在 `1.0.0-rc.4` 中通过测试。**
> 最大的遗留集成阻塞项——jakarta（Spring Boot 3/4）适配，即反馈 **P0-1**——顺延到 rc.4 之后，
> 见 [ROADMAP](ROADMAP.md)。

## 一、目标与范围

rc.3 只经过本仓库 demo 与测试验证。其首个外部使用方（path-finder）以 **手工装配 core**
（`upload-file-core` + `upload-file-store-redis`，官方 starter/servlet 因 `javax.servlet` 无法用于
Spring Boot 4）运行在 JDK 26 / Spring Boot 4 上，使下列缺口变得具体。rc.4 关闭其中杠杆最高的子集，
其余留在路线图。

| 编号 | 反馈缺口 | 说明 | rc.4 任务 |
| --- | --- | --- | --- |
| P0-1（顺延） | jakarta 兼容性 | 官方 starter/servlet 依赖 `javax.servlet`，无法运行于 Spring Boot 3/4；使用方降级为 core 手工装配 | 1.0.0 之后 |
| P0-2 | 无稳定「读 / 取消」契约 | confirm 阶段按目录约定推导合并产物路径，而非读取返回的 `finalPath`；放弃的上传与已合并未 confirm 产物只能等 TTL + 调度器 | T14、T15 |
| P0-3 | 清理与孤儿语义不清 | 手工装配下 `upload-file.cleanup.*` / `async-merge.*` 看似已配置实为死配置；孤儿回收语义未文档化 | T18 |
| P1-4 | HTTP 层错误语义被吞 | 裸泛型异常落入接入层 `500` 兜底；客户端可恢复的失败被误报为服务端故障 | T16、T17 |
| P1-7 | AccessControl 缺少对接外部认证说明 | 恒用 `PermitAllAccessControl` 且从不带 token，SPI 对已有登录态的接入方显得不可用 | T18 |
| P1-8 | Range/下载解析被重复实现 | `DownloadRange.parse` 等未被文档化为可单独复用的公共工具 | T18 |

## 二、架构与约束

沿用模块化分层与「业务逻辑全部进 core，servlet / starter 只做装配与映射」：

```
upload-file (父 POM / 聚合器)
├── upload-file-core                        # T14~T17 核心逻辑与异常
├── upload-file-servlet                     # T17 错误映射对齐 + cancel action
├── upload-file-spring-boot-starter         # rc.4 不变
├── upload-file-store-jdbc   [可选]         # rc.4 不变
├── upload-file-store-redis  [可选]         # rc.4 不变
└── example/…                               # 仅文档/demo 说明
```

约束：
- rc.4 **纯增量**：无新增配置项、默认配置下零行为变更、无 SPI 破坏——公开错误契约是**变强**（宽泛 catch 仍有效）；
- `getTask` / `cancelUpload` 加在具体服务类（`ResumableUploadService`）而非 SPI 上，自定义装配不受影响；
- 接入层只认一个 `UploadErrorCode` 接口做映射，状态码由异常自持，绝不靠类型或消息猜测。

## 三、任务拆解

### T14 confirm 阶段稳定「按 identifier 读」（P0-2）

- **涉及文件**：`ResumableUploadService`（core）、README / docs。
- **方案**：新增 `Optional<UploadTask> getTask(String identifier)` 返回当前 store 记录（活快照，只读对待）。
  文档明确：合并成功后，接入层 confirm 阶段**必须**通过 `UploadTask.getFinalPath()` 定位合并产物
  （`UploadResult` / `MergeStatus` 亦携带），绝不自行按 `{storage-dir}/files/<id>/<fileName>` 目录约定或
  落盘命名策略猜测。
- **验收**：`getTask` 返回记录或空；`finalPath` 为 confirm 权威定位；不再依赖内部目录约定；文档给出
  confirm 示例。
- **预估**：0.5 人天。

### T15 显式取消与数据回收（P0-2）

- **涉及文件**：`ResumableUploadService`（core）、`AccessControl`、`UploadServlet`。
- **方案**：新增 `boolean cancelUpload(identifier [, token])`。在 per-identifier 锁内按崩溃安全顺序回收——
  先分片、再合并产物目录、后任务记录；任务不存在返回 `false`，异步合并 PENDING/RUNNING 期间抛
  `UploadMergeConflictException`（`409`）。由新 `AccessControl.ACTION_CANCEL` 守卫。Servlet 暴露：
  `POST /upload?action=cancel&identifier=...`。
- **验收**：cancel 分别返回 200（已删）/ 404（不存在）/ 409（异步合并进行中）/ 401（拒绝），JSON 响应不外泄
  内部状态；被取消的 identifier 可重新发起全新上传。
- **预估**：1 人天。

### T16 稳定的 `UploadErrorCode` HTTP 语义（P1-4）

- **涉及文件**：`cn.chenxinjie.uploadfile.core.exception` 与 `ResumableUploadService`（core）。
- **方案**：引入 `UploadErrorCode.getHttpStatusCode()`。既有异常实现之——`ChecksumMismatchException` → `400`、
  `AccessDeniedException` → `401`、`QuotaExceededException` → `507`。另新增三个带码异常，均为其所替代的
  泛型异常子类，宽泛 catch 不受影响：
  `UploadValidationException`（`400`，`IllegalArgumentException` 子类）、`UploadTaskNotFoundException`（`404`，
  `NoSuchElementException` 子类）、`UploadMergeConflictException`（`409`，`IllegalStateException` 子类）。
  将分片校验 / 元数据一致性 / `merge` / `submitMerge` 中的裸泛型抛错替换为上述带码类型。
- **验收**：失败场景不变但携带稳定文档化状态码；catch 泛型类型的既有代码不受影响；接入方只需判一次
  `UploadErrorCode` 即可完成映射；客户端可区分 `400` 与 `404` 与 `409`。
- **预估**：1 人天。

### T17 Servlet 错误映射对齐与 `cancel` action（P1-4）

- **涉及文件**：`UploadServlet`。
- **方案**：所有失败按 `UploadErrorCode` 映射（`400/401/404/409/507`）并以 JSON 返回，取代一刀切的 `400`；
  注册 `action=cancel` 端点（T15）；`409` 取消响应使用通用消息，不外泄内部状态。
- **验收**：servlet 状态码与文档 `UploadErrorCode` 表一致；cancel 行为同 T15；既有 servlet 测试保持绿色。
- **预估**：0.5 人天。

### T18 文档：清理、手工装配、认证桥接与工具复用（P0-3 / P1-7 / P1-8）

- **涉及文件**：`README(.zh-CN).md`、`docs/API(.zh-CN).md`、`docs/ROADMAP(.zh-CN).md`、`CHANGELOG(.zh-CN).md`、
  `doc/user-feedback/upload-file-usage-feedback.md`。
- **方案**：
  - **清理 / 孤儿语义**：`StorageCleanupService` 回收什么（未完成任务分片、已合并未 confirm 产物、临时文件）
    与何时回收；手工装配 core 的接入方须自行启动清理服务；
  - **手工装配责任清单**：`upload-file.*` 属性（含 `cleanup.*`、`async-merge.*`）仅由官方 starter 消费；
    手工装配 core 时由调用方自理——消除「看似已配置实为死配置」；
  - **`UploadErrorCode` 状态码表**、confirm 阶段 `finalPath` 契约与 `getTask`/`cancelUpload` 用法示例；
  - **AccessControl 章节**：(a) 直接配 `TokenAccessControl`（token 经 `X-Access-Token` 头 / `token` 参数）；
    (b) 自定义 `AccessControl` 委托到既有 Bearer/SSO 登录态；
  - **`DownloadRange`** 文档化为可单独复用的 Range 解析工具；
  - 把驱动本次改版的真实使用方反馈原文收录到 `doc/user-feedback/`。
- **验收**：反馈 §7 中 P0-2 / P0-3 / P1-4 / P1-7 / P1-8 的非代码项均有文档答复；新接入方读完 README 即知
  starter 装配了什么、手工装配还需自理什么。
- **预估**：1 人天。

### （顺延）jakarta 适配（反馈 P0-1）

- **不在 rc.4 范围。** 为 `upload-file-servlet` 与 `upload-file-spring-boot-starter` 增加 Spring Boot 3/4
  （`jakarta.servlet`）产物，同时保留 javax 版兼容。core 在 jakarta 技术栈上已可手工装配直接使用，因此
  本项为产物/打包工作。排期为 `1.0.0` 之后的计划项（见更新日志「Unreleased」）。

## 四、新增 API 与错误契约（rc.4 无新增配置项）

- `ResumableUploadService.getTask(identifier)` → `Optional<UploadTask>`——其 `finalPath` 是 confirm 阶段合并产物
  的权威定位；
- `ResumableUploadService.cancelUpload(identifier [, token])` → `boolean`——显式取消 + 数据回收；
- `AccessControl.ACTION_CANCEL` 守卫动作；
- `POST /upload?action=cancel&identifier=...`（HTTP `200/404/409/401`，JSON 响应）；
- `UploadErrorCode` 状态码表：

| HTTP | 异常 | 含义 |
| --- | --- | --- |
| `400` | `UploadValidationException` / `ChecksumMismatchException` | 客户端可恢复：参数非法、元数据不一致、超大小限制、校验失败 |
| `401` | `AccessDeniedException` | 令牌缺失或错误 |
| `404` | `UploadTaskNotFoundException` | 上传任务不存在 |
| `409` | `UploadMergeConflictException` | 合并状态冲突：已合并后上传、缺分片、异步合并进行中取消 |
| `507` | `QuotaExceededException` | 超出容量配额 |

## 五、兼容性

- 纯增量：无新增配置项、默认配置零行为变更，升级 rc.3 → rc.4 保持既有行为；
- 新异常为原先所抛泛型类型（`IllegalArgumentException` / `NoSuchElementException` / `IllegalStateException`）的
  子类，宽泛 catch 不受影响；接入方只需一个 `UploadErrorCode` 判断即可映射状态码；
- `getTask` / `cancelUpload` 加在具体服务而非 SPI 上，自定义装配与 store 不受影响；
- servlet 失败映射由「除配额/鉴权外一律 `400`」收窄为文档化的精确状态码，是对客户端的改进而非契约破坏。

## 六、测试计划

- 单元测试沿用 JUnit 4 + JaCoCo；rc.4 发布含 core 205 例、servlet 51 例，全部通过；
- 新增覆盖：`cancelUpload` `200/404/409/401` 与幂等（不存在 → `false`）；`getTask` 存在/不存在；带码异常
  状态码；servlet `cancel` action 与 `UploadErrorCode` 映射；既有 merge / 异步合并状态机回归。

## 七、文档与示例更新

- `README(.zh-CN).md` + `docs/API(.zh-CN).md`：confirm 阶段 `finalPath` 契约、`getTask` / `cancelUpload` 与
  `action=cancel`、`UploadErrorCode` 状态码表、清理与孤儿回收语义、手工装配（core）自理清单、
  AccessControl Bearer/SSO 说明、`DownloadRange` 复用；
- `docs/ROADMAP(.zh-CN).md`：标记 rc.4 已完成、jakarta 适配为后续计划项；
- `CHANGELOG(.zh-CN).md`：记录 rc.4 反馈驱动变更并注明 jakarta 顺延；
- `doc/user-feedback/upload-file-usage-feedback.md`：收录使用方反馈原文；
- 示例/demo 代码不受影响（demo 走官方 starter）。

## 八、里程碑与发布

1. **M1**：T16 + T17——端到端稳定错误语义（core 异常 + servlet 对齐）；
2. **M2**：T14 + T15——confirm 阶段稳定「读 / 取消」契约；
3. **M3**：T18——文档与反馈原文收录；
4. **M4**：版本号 `1.0.0-rc.4`，按既有 release profile 发布 Maven Central；发布公告注明 jakarta 适配
   （反馈 P0-1）为下一计划项。
