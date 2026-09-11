# V1.0.0-rc.6 任务开发计划（HTTP 层商业化可接入：安全与审计对齐）

> 🇺🇸 [English](PLAN-V1.0.0-rc.6.md)
>
> rc.5 交付 jakarta starter 后，首个真实接入方评估（path-finder 的 `ADR-001-upload-file-starter-jakarta` +
> `UPGRADE-upload-file-starter-jakarta`，结论「**不迁移**」）表明：`-jakarta` 产物只是 **javax starter** 的
> drop-in，而不是「core 手工装配 + 自研 MVC 端点」方案的 drop-in。根因：(1) servlet 无条件注册且优先于 MVC；
> (2) HTTP 错误/审计契约被整体接管且不可定制；(3) 拒绝只能以写死的 `401` 抛异常表达、决策点不可观测；
> (4) multipart 与细粒度 HTTP 行为存在差异。rc.6 把官方 HTTP 层改造成商业项目可直接采用：端点可控、
> 错误码/错误体规范、`AccessControl` 决策路径对象化 + 审计钩子、multipart 策略化、默认行为收敛、迁移向导。
> 反馈来源：`doc/user-feedback/upload-file-usage-feedback.md`（§7 P0-2/P1-4/P1-7）与 path-finder
> ADR/UPGRADE 评估结论。
>
> ✅ **状态：`1.0.0-rc.6` 主体已实现并发布。** T25–T31、T34 完成：端点控制、增量式决策返回 `AccessControl` +
> 审计钩子、符号错误码 + 统一错误体、multipart 策略、servlet 行为收敛均已交付——见
> [更新日志](../CHANGELOG.zh-CN.md)。
>
> ⚠️ **未完成项（后续补做）**：T32 的「自研 MVC 端点 → 官方 Servlet」迁移向导（坐标/差异矩阵、breaking 默认
> 一行配置、`check()`→`decide()` 迁移片段）尚未成文；T33 的企业化接线目前仅落在 `boot4-demo` 的
> `application.yml` 注释，尚缺计划中的 demo 配置类（`AccessControl.decide()` + `AccessDecision.deny(403,...)`
> + `AccessControlListener` 审计示例）。

## 一、目标与范围

**主题**：让官方 servlet/starter 的 HTTP 层能被「已有会话鉴权 + 统一响应信封 + 自研下载端点」的商业系统
**直接采用**——同时缩小默认暴露面、补齐审计可观测。

| 编号 | 缺口 | 说明 | rc.6 任务 |
| --- | --- | --- | --- |
| G1 | 端点注册不可控 | 两个 `ServletRegistrationBean` 无条件注册（无开关、无可覆盖点）；无「纯 bean 模式」；`/download` 默认即注册 | T29 |
| G2 | 拒绝只能以 `401` 抛异常表达 | `AccessControl` 无决策对象；无法区分 `401`（未认证）与 `403`（越权）；决策点不可观测 | T25、T26 |
| G3 | 无审计可观测 | 放行/拒绝决策不产生结构化留痕，宿主须自行埋点 | T26 |
| G4 | HTTP 失败不可定制且部分误码 | 固定 Gson 信封（端点空对象当错误体）、无符号错误码；`GET /upload` 缺失/未知 action 被当 progress；merge/cancel/status 的非 `UploadErrorCode` 服务端故障被吞成 `400` | T27、T28 |
| G5 | starter multipart 上限覆写 Spring 全局语义 | `max-chunk-size` / `max-request-size` 注入 `@MultipartConfig`，绕过 `spring.servlet.multipart.*` | T30 |
| G6 | 第三方无迁移指引/样板 | README/API/demo 只覆盖「从零用官方端点」，缺「自研 MVC 端点 → 官方 Servlet」路径 | T32、T33 |

## 二、架构与约束

rc.6 **不新增 Maven 产物**；javax 与 jakarta 的 servlet/starter 两条线以同一 `1.0.0-rc.6` 版本**同步演进**
（源码孪生保持镜像，共享逻辑一律留在 `upload-file-core`）。

- **增量优先（additive-first）**：每个新能力都是新增（新属性 / 新 SPI / 新方法或默认方法），不删除既有
  实现方依赖的任何成员。默认行为的 breaking 仅限两处缺陷修正（T28）与 `/download` 最小暴露默认（T29）。
- **off-by-default（零回归）**：新属性/SPI 的默认值完整复刻 rc.5 行为——错误体规范、access-log、端点开关、
  multipart 策略、可选的 cancel-not-found 映射。
- **`AccessControl` 演进而非破坏**：既有 `void check(...) throws AccessDeniedException` 仍是合法实现点
  （`@Deprecated`，经默认方法桥接）；新增返回决策对象的方法为纯增量。Boot 2 / javax 手工装配使用方在 rc.6
  不改代码、行为不变，除非主动选用新 API。见 T25。
- **商业安全基线**：最小默认暴露面（`/download` 关闭）；拒绝状态码由宿主的决策决定（`401`/`403` 可区分）；
  MVC 与 Servlet 路径经同一钩子可观测决策；符号错误码目录**单一事实来源**，代码与文档同源。
- 不删除任何 `upload-file.*` 属性，既有属性默认值不变；无磁盘布局 / 任务元数据 / 数据格式变化
  （升级仅重启）。
- core 手工装配保持完整支持，与 starter 同步升级。

## 三、任务拆解

### T25 决策返回式 `AccessControl`（增量桥接）（core，G2）

- **涉及文件**：`AccessControl`、`PermitAllAccessControl`、`TokenAccessControl`、`AccessDeniedException`
  （可选 `status`）、新增 `AccessDecision`；`ResumableUploadService` / `ResumableDownloadService` 调用点。
- **方案**（增量，既有实现零破坏）：
  - 保留 `void check(identifier, action, token) throws AccessDeniedException` 原签名但标 `@Deprecated`；
  - 新增 `default AccessDecision decide(identifier, action, token)`，默认实现调用 `check()`：「正常返回」→
    `allow()`；「抛 `AccessDeniedException`」→ `deny(e.getStatusCode() or 401, e.getMessage())`。新实现覆盖
    `decide()`（可不再实现 `check()`）；
  - `AccessDecision.allow()` / `AccessDecision.deny(status, reason)`；`AccessDeniedException` 增加可选
    status，缺省 `401`；
  - core 服务调用 `decide()`；拒绝时抛出携带决策 status 的 `AccessDeniedException`——未选用的调用方 HTTP
    语义不变，`deny(403)` 在 HTTP 层表现为 `403`。
- **验收**：内置实现、`decide()`↔`check()` 双向桥接、状态传递均有单测；仅覆盖 `check()` 的旧实现仍可编译
  且语义一致；`deny(403)` 到达 HTTP 层为 `403`；默认 `401` 不变。
- **预估**：1 人天。

### T26 决策钩子 + access-log（core，G2/G3）

- **涉及文件**：新增可选 SPI `AccessControlListener`；`ResumableUploadService` /
  `ResumableDownloadService` 增加 `addAccessControlListener(...)`；starter 自动配置聚合
  `ObjectProvider<AccessControlListener>`；`observability.access-log`（默认 `false`）。
- **方案**：每个入口的访问检查收敛到一个共享门控：(a) 求值 `decide()` → (b) 通知全部监听器
  `onDecision(identifier, action, decision, elapsedNanos)`（`elapsed` = 该次决策自身耗时）→
  (c) `observability.access-log=true` 时输出一行结构化日志（action/identifier/决策/status/elapsedMs，沿用
  `CLEANUP_STATS_LOG` 风格）。门控在 core，故 MVC、Servlet、下载路径在任何拒绝抛出前事件一致——宿主
  （path-finder）实现 `AccessControlListener` 即可写自己的 FORBIDDEN 审计行，无需在每个拒绝点埋
  `LogService`。
- **验收**：上传/进度/合并/异步合并/下载/取消在 MVC（service）与 Servlet 两条流程的 allow/deny 都触发
  监听器；access-log 行可开关；无监听器且日志关闭 = 行为不变。
- **预估**：1 人天。

### T27 稳定符号错误码 + 统一错误体（core，G4）

- **涉及文件**：`UploadErrorCode` 增加 `code()`；各带码异常返回稳定常量；新增 `UploadErrorCodes` 目录类
  （单一事实来源）；新增模型 `UploadHttpError`（`code/status/message/identifier/action`）与
  `UploadErrorRenderer` SPI（内置 `legacy` 与 `standard` 渲染）。
- **方案**：
  - **符号码始终可用（不受开关控制）**：`UploadErrorCode.code()` 为默认方法，返回 `UploadErrorCodes`
    显式目录（**非类名派生**，改名不漂移）；目录是渲染器与 API.md（T32）共读的唯一来源；
  - **错误体形态与符号码正交**：可选属性只选择**错误体形态**——`legacy`（rc.5 端点空对象 JSON，默认）或
    `standard`（`UploadHttpError` + 目录码）。宿主需要自有信封（如 path-finder `ApiResponse`）时应提供
    `UploadErrorRenderer` Bean，而非用 `standard`。
- **验收**：每个带码异常返回非空目录码；代码稳定性由测试锁定（改名必须改目录而非漂移）；`legacy` 输出与
  rc.5 逐字节一致；渲染器单测。
- **预估**：0.5 人天。

### T28 Servlet 行为收敛 + 错误渲染（servlet 两线，G4）

- **涉及文件**：`upload-file-servlet` 与 `upload-file-servlet-jakarta` 的 `UploadServlet` /
  `DownloadServlet` / `UploadFileContext` 及测试套件。
- **方案**（缺陷修正作用于**默认**，标记 breaking）：
  - `GET /upload` 必须有已知 action：**缺失** `action` 或未知值返回 `400`（`MISSING_ACTION` /
    `UPLOAD_UNKNOWN_ACTION`）。注意：今天「裸 `GET /upload?identifier=X` 轮询进度」的隐式用法（被静默当作
    progress）将变为 `400`——列入验收矩阵；
  - merge/cancel/status/progress 的非 `UploadErrorCode` 服务端故障返回 `500` 且文案泛化（原：吞成 `400`）；
  - 新增 init-param / 属性：错误体 `legacy|standard`（默认 `legacy`）、可选 `cancel-not-found-status`
    （默认 `404`，可 `200` 幂等回收）。两种模式下成功体均不变。
- **验收**：javax 与 jakarta 测试套件互为镜像；验收矩阵显式含 `GET` 缺 action 与未知 action → `400`；
  `legacy` 模式对既有全部用例保持 rc.5 逐字节输出。
- **预估**：1.5 人天。

### T29 端点注册可控 + 纯 bean 模式（starter 两线，G1）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration` / `UploadFileProperties` / 元数据。
- **方案**（统一收口到 `upload-file.endpoint.*` 一组）：
  ```yaml
  upload-file:
    endpoint:
      enabled: true           # 总开关；false = 纯 bean 模式（只装配服务 Bean，不注册 Servlet）
      upload-enabled: true    # 注册 /upload
      download-enabled: false # /download 默认关闭（breaking，最小暴露面）
  ```
  Servlet 注册 Bean 增加 `@ConditionalOnMissingBean`，宿主可用自定义注册覆盖。这些开关**仅作用于 starter**
  ——纯 Servlet 宿主本就在 web.xml/注解映射处决定是否注册，不读取它们。
- **验收**：默认上下文注册 `/upload`、不注册 `/download`；`download-enabled=true` 后恢复；`endpoint.enabled
  =false` 时服务 Bean 全在、无 Servlet；自定义 `ServletRegistrationBean` 覆盖自动注册；不再有第二套主开关
  造成命名重叠。
- **预估**：1 人天。

### T30 multipart 策略（starter 两线，G5）

- **涉及文件**：两条 starter 的 `UploadFileAutoConfiguration` / `UploadFileProperties`；starter 测试。
- **方案**：新增 `upload-file.multipart.strategy`（`component` | `spring` | `unlimited`，默认 `component` =
  rc.5 行为）：
  - `spring` — 依 `spring.servlet.multipart.*` 构建 Servlet `MultipartConfigElement`。**文档警示：Boot 默认
    `max-file-size=1MB / max-request-size=10MB`，应用未调大时 5MB 分片会在容器层（进入业务逻辑前）被拒。**
    迁移向导给出所需的 `spring.servlet.multipart.max-file-size/max-request-size` 对齐值；
  - `unlimited` — 关闭容器上限（`-1`），仅服务层 `max-chunk-size` / `max-file-size` 业务校验，
    `upload-file.max-request-size` 忽略。**文档警示：关闭了容器级 DoS 防护——超大请求体会先落临时文件再被
    服务层拒绝。**
- **验收**：两条 starter 各测三策略；默认（`component`）不变；两条警示均写入配置表。
- **预估**：0.5 人天。

### T31 starter 属性接线 + javax/jakarta 对齐测试（G1/G3/G4/G5 装配）

- **涉及文件**：两条 starter 的自动配置 + 测试套件（默认值、存储选择、清理、异步合并、访问控制、
  servlet 注册含 `action=cancel`、端点、错误体、access-log）。
- **方案**：把 rc.6 全部新属性从 `UploadFileProperties` 接入自动配置与 servlet init-param；断言 javax 与
  jakarta starter 在 rc.6 全量属性集与 Bean 图上等价，含新开关与 T28/T29/T30 行为对齐。
- **验收**：两套 starter 测试全绿；两线属性集 / Bean 图等价；自定义 Bean 仍经 `@ConditionalOnMissingBean`
  可覆盖。
- **预估**：1.5 人天。

### T32 迁移向导 + 错误码目录（G6 文档）

- **涉及文件**：`README(.zh-CN).md`、`docs/API(.zh-CN).md`、`docs/DESIGN(.zh-CN).md`。
- **方案**：新增「自研 MVC 端点 / core 手工装配 → 官方 Servlet（rc.6）」向导：javax↔jakarta 坐标矩阵、
  差异矩阵（成功对象、失败体、拒绝 `401`/`403`、缺失/未知 action、取消语义、`/download` 暴露面、
  multipart）、各 breaking 默认的一行配置、`AccessControl` 增量桥接迁移片段（`check()` vs `decide()`）、
  由同一 `UploadErrorCodes` 来源渲染的符号错误码目录、最小暴露建议（`endpoint.download-enabled` 策略、
  经 `AccessControlListener` 审计）。
- **验收**：path-finder 类读者读完即知翻哪些开关、动哪几个文件；API.md 错误码目录与 `UploadErrorCodes`
  一致（单一来源）；`/download` 默认关闭的升级提示醒目（README 顶部 + CHANGELOG）。
- **预估**：1 人天。

### T33 企业化 demo 增强（G6 示例）

- **涉及文件**：`example/upload-file-boot4-demo`（前端 + `application.yml` + 一个 demo 配置类）。
- **方案**：在 Boot 4 demo 中以文档化 profile/示例给出商业接线：会话归属 `AccessControl`（覆盖 `decide()`
  返回 `AccessDecision.deny(403, ...)`）、写审计行的 `AccessControlListener`、`endpoint.download-enabled
  =false`，以及一个 `standard` 错误体变体。Boot 2 / javax demo 仅加简短说明。
- **验收**：demo 在 Boot 4 上以企业化接线运行；走查写入 README。
- **预估**：1 人天。

### T34 更新日志 / 路线图 / 发布（G1–G6 同步 + 发布）

- **涉及文件**：`CHANGELOG(.zh-CN).md`、`docs/ROADMAP(.zh-CN).md`、`docs/DESIGN(.zh-CN).md`、
  `docs/PLAN-*` 状态翻转、父 POM 与各模块 POM 版本 `1.0.0-rc.5 → 1.0.0-rc.6`、`.flattened-pom.xml`、demo。
- **方案**：成文 rc.6 发布条目（breaking 默认清单 + 增量清单）并置顶 `/download` 默认关闭的升级提示与逐项
  一行配置；以 rc.6 向导标记 rc.5-era jakarta 采纳阻塞关闭；全部测试通过后翻转 PLAN 状态；JDK 17+ 全量
  `mvn verify`（javax 线仍可 `-pl` 在 JDK 8 构建）；按既有 release profile 发布两线。Boot 2 / javax 线同以
  `1.0.0-rc.6` 发布，因 AccessControl 保持增量、无强制改码，javax 使用方仅得指引、非 breaking 迁移。
- **验收**：CHANGELOG 列出每个 breaking 默认与其一行配置；全模块绿；发布公告主打「官方 HTTP 层可被商业
  项目直接采用」。
- **预估**：1 人天。

## 四、新增配置与 SPI 面

| 类别 | 项 | 默认 | 是否 breaking |
| --- | --- | --- | --- |
| 属性 | `upload-file.endpoint.enabled`（总开关；`false` = 纯 bean 模式） | `true` | 否（`false` 时为新模式） |
| 属性 | `upload-file.endpoint.upload-enabled` | `true` | 否 |
| 属性 | `upload-file.endpoint.download-enabled` | `false` | **是**（原默认注册） |
| 属性 | `upload-file.http.error-body`（`legacy`/`standard`） | `legacy` | 否（`standard` 需显式开启） |
| 属性 | `upload-file.http.cancel-not-found-status` | `404` | 否（可 `200`） |
| 属性 | `upload-file.observability.access-log` | `false` | 否 |
| 属性 | `upload-file.multipart.strategy` | `component` | 否 |
| SPI/API | `AccessControl.decide(...)` 新增默认方法；`check(...)` 保留并 `@Deprecated` | `check()` 桥接 | **否**（增量） |
| SPI | `AccessControlListener`（新增） | 无 | 否 |
| SPI | `UploadErrorRenderer` + `UploadHttpError`（新增） | `legacy` | 否 |
| API | `UploadErrorCode.code()` 符号码 + `UploadErrorCodes` 目录 | 始终开启、取自目录 | 否 |

> T28 的行为收敛（`GET` 缺失/未知 action → `400`；非 `UploadErrorCode` 服务端故障 → `500`）与 T29 的
> `/download` 默认关闭改变**默认值**，记入 CHANGELOG breaking，并以回归矩阵断言新契约。

## 五、兼容性（rc.5 → rc.6）

| 行为 | rc.5 | rc.6 | 说明 |
| --- | --- | --- | --- |
| `AccessControl` | `void check(...)` 抛异常 | `check()` 保留并 `@Deprecated` + 新增 `decide()` 返回 `AccessDecision` | 增量；旧实现零改动 |
| `/download` 注册（starter） | 默认注册 | 默认不注册，需 `endpoint.download-enabled=true` | breaking；最小暴露面；醒目升级提示 |
| `GET /upload` 缺失/未知 action | 当作 progress | `400`（`MISSING_ACTION` / `UPLOAD_UNKNOWN_ACTION`） | breaking；缺陷修正 |
| merge/cancel/status 服务端故障 | 吞成 `400` | `500` | breaking；缺陷修正 |
| 失败响应体 | 端点空对象 JSON | 不变（`legacy`）；`standard` 需显式开启 | off-by-default |
| 拒绝状态码 | 恒 `401` | 由宿主决策（`401`/`403`），经 `decide()` | 增量 |
| 审计 | 组件无内置 | `AccessControlListener` + `access-log` | off-by-default |
| 错误码 | 无 | 符号码始终可用 | 增量 |
| multipart 上限 | 组件属性 → `@MultipartConfig` | `component`（默认）/ `spring` / `unlimited` | off-by-default |
| 成功体 / 端点 / 既有 `upload-file.*` 键 | — | 不变 | — |

- 不新增产物。core 手工装配继续可用，且在不选用 `decide()` 时不受影响。
- 无磁盘布局 / 任务元数据 / 数据格式变化；升级仅需重启（无数据迁移）。
- javax 与 jakarta 孪生产物仍互斥于同一 classpath（延续 rc.5 约定）。

## 六、测试计划

- core：`AccessDecision`、`decide()`↔`check()` 双向桥接、内置实现（T25）、listener + access-log（T26）、
  渲染器 + 目录稳定性（T27）单测；compat 回归对「默认保持 rc.5」的部分断言逐字节不变。
- servlet（javax + jakarta，互为镜像）：行为收敛矩阵（含 `GET` 缺 action 与未知 action → `400`）、
  `legacy`/`standard` 错误体、`cancel-not-found-status`、服务端故障 `500`。
- starter（javax + jakarta，互为镜像）：端点总开关 + 单端点开关 + 纯 bean 模式、multipart 策略（含
  `spring` 1MB 默认上限的警示用例）、属性接线等价、自定义注册覆盖。
- demo：Boot 4 企业化接线手工 E2E；Boot 2 demo 回归。
- JDK 17+ 全 reactor `mvn verify`；各模块保留 JaCoCo。

## 七、文档与示例更新

- `README(.zh-CN).md` / `docs/API(.zh-CN).md`：迁移向导（T32）、由 `UploadErrorCodes` 渲染的错误码目录、
  新属性表、breaking 默认说明（含置顶的 `/download` 升级提示）；`docs/DESIGN(.zh-CN).md`：决策返回式
  `AccessControl`、listener、renderer、端点模型。
- `example/upload-file-boot4-demo`：企业化接线示例（T33）。
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`：rc.6 计划条目 → 发布后翻转已实现。

## 八、里程碑与发布

1. **M1**（T25、T27）：core `AccessDecision` 桥接 + 错误码/错误体——地基，既有测试全绿；
2. **M2**（T26）：决策钩子 + `access-log`；
3. **M3**（T28）：servlet 两线行为收敛 + 错误渲染；
4. **M4**（T29、T30、T31）：starter 端点控制、multipart 策略、属性接线 + 对齐套件；
5. **M5**（T32、T33）：迁移向导 + 企业化 demo；
6. **M6**（T34）：版本 `1.0.0-rc.5 → 1.0.0-rc.6`、JDK 17+ 全量 `mvn verify`、CHANGELOG/ROADMAP 同步并发布；
   公告主打「官方 HTTP 层可被商业项目直接采用（安全与审计对齐）」。
