# V1.0.0-rc.9 任务开发计划（冻结期内安全收口：配额绕过/默认无上限/校验可跳过/路径遍历/日志注入/清理中断/有界分片写入）

> 🇺🇸 [English](PLAN-V1.0.0-rc.9.md)
>
> rc.8 冻结了公开 API 与 `upload-file.*` 属性面，[rc.8 使用反馈](../doc/user-feedback/upload-file-rc8-usage-feedback.md)
> 确认冻结面在生产消费方手中成立。随后以资深 Maven 依赖开发者视角对全仓库做安全评审，在冻结面上发现
> **7 个问题（2 高危 + 5 中危）**：配额与大小限制信任客户端声明值、未按实际字节计数（H1）；starter 默认
> `max-chunk-size=-1`，请求体无上限（H2）；`verify-checksum` 开启时缺失 `chunkMd5` 会完全跳过校验（M1）；
> 下载 `finalPath` 规范化后未做前缀校验，可逃逸合并目录（M2）；访问日志拼接攻击者可控字段且未消毒（M3）；
> 一条脏数据中断整个孤儿清理（M4）；超限分片先完整落盘再校验（M5）。
>
> ✅ **状态：已在 `1.0.0-rc.9`（2026-09-28）实现并发布。** T54–T61 全部完成：按实际字节计数、默认收紧 +
> 启动失败、`require-checksum`、下载路径规范化前缀校验、日志消毒、孤儿清理逐条隔离、带字节上限的
> `saveChunk(..., maxBytes)` 重载——见[更新日志](../CHANGELOG.zh-CN.md)。rc.9 之后冻结面仍然成立：
> `1.0.0` 仅做版本号提升，并回退 rc.9 对 `saveChunk` 返回类型的短暂改动（恢复 3 参 `void` 签名 + 新增
> 4 参 `default` 重载，保持二进制兼容，见 §10）。
>
> 反馈来源：`doc/user-feedback/upload-file-rc8-usage-feedback.md`。

## 一、目标与范围

**主题**：在 **API 冻结期内做安全收口**——修复评审发现的 7 个问题（配额绕过、默认无上限、校验可跳过、
路径遍历、日志注入、清理中断、先落盘后校验），只做 **additive 变更与默认收紧**，不新增特性、不破坏任何
冻结契约。rc.9 之后 API 与 `upload-file.*` 属性面保持冻结；`1.0.0` 仅版本号与发布公告。

| 编号 | 缺口 | 说明 | rc.9 任务 |
| --- | --- | --- | --- |
| G28 (H1) | 配额/大小限制信任声明值 | `uploadChunk` 按客户端声明 `fileSize` 计数；声明 `0`/负值可绕过 `maxChunkBytes` 与配额——磁盘耗尽攻击面 | T54 |
| G29 (H2) | 默认请求配置无上限 | `max-chunk-size` 默认为 `-1`；宿主只配服务级上限时请求体无上限——默认不安全 | T55 |
| G30 (M1) | MD5 校验可跳过 | `verify-checksum=true` 时缺失 `chunkMd5` 的分片直接跳过校验而非失败 | T56 |
| G31 (M2) | 下载 `finalPath` 可逃逸合并目录 | 前缀校验作用在未规范化路径上；构造路径可绕过规范化读取合并目录之外的文件 | T57 |
| G32 (M3) | 访问日志注入 | 访问日志拼接攻击者可控的 identifier/action 字段，未过滤换行/回车/控制字符（CWE-117） | T58 |
| G33 (M4) | 单条脏数据中断孤儿清理 | 一条损坏的 identifier 抛异常即中断整个 `cleanupOrphans` 轮次 | T59 |
| G34 (M5) | 超限分片先落盘后校验 | 分片先完整写入磁盘，再与 `maxChunkBytes` 比较——写放大 | T60 |

## 二、架构与约束

rc.9 **不新增 Maven 产物、不新增模块**；javax 与 jakarta 两线仍以同一 `1.0.0-rc.9` 对齐演进，
共享逻辑一律留在 `upload-file-core`。

- **冻结兼容**：每项变更都是 **additive** 的 API/SPI 新增（新属性 `require-checksum`、新 `default`
  方法 `saveChunk(..., maxBytes)`）或**默认值收紧**（`max-chunk-size` 10 MB、三层无界启动失败）；
  不删除任何成员、不改既有签名（rc.9 对 `saveChunk` 返回类型的短暂改动在 GA 回退，见 §10）。
- **以服务端为准**：大小/配额记账必须来自**磁盘上观测到的实际字节**（或计数流），绝不信任客户端声明值。
- **fail-fast 优先于告警**：任一维度都无上限的端点配置不得启动；显式配置永远生效并保持原行为。
- **可回滚**：无磁盘布局 / 任务元数据格式变化；无 Redis 索引结构变化；默认 `task-store` 路径与 rc.8
  逐字节一致。
- **API 冻结成立**：rc.9 是冻结期内的安全收口；`1.0.0` 除 `saveChunk` 二进制兼容回退外不改行为（§10）。

## 三、任务拆解

### T54 配额/大小限制按实际字节计数（core，H1 / G28）

- **涉及文件**：`upload-file-core/.../core/service/ResumableUploadService.java`（uploadChunk 路径）、
  `ResumableUploadServiceTest`（+ `CoreEdgeCoverageTest`）。
- **方案**：
  - 对负值 `fileSize` 前置拒绝（非法声明值，`400` 类错误）；
  - 保存后复核分片的**磁盘实际长度**与 `maxChunkBytes`，超限分片**删除并拒绝**——记账不再信任
    客户端声明值；
  - 配额计数保持按实际字节（T60 的有界写入保证声明值无法虚增计数）。
- **验收**：声明 `fileSize=0` 或负值不能绕过 `maxChunkBytes`/配额；超限分片无磁盘残留；正常分片上传
  行为与 rc.8 逐字节一致。
- **预估**：1 人天。

### T55 默认收紧 + 启动失败（starter 两线 + servlet 两线，H2 / G29）

- **涉及文件**：`UploadFileProperties`（两条 starter 线；`max-chunk-size` 默认 `10 MB`）、
  `UploadFileAutoConfiguration`（两条 starter 线；请求上限推导 + fail-fast）、
  `UploadFileContext`（两条纯 Servlet 线；init-param 配置同样推导）、测试。
- **方案**：
  - `max-chunk-size` 默认 `-1` → **10 MB**（breaking-default：从未配置的部署获得有界分片；显式值不变）；
  - 未配置的 `max-request-size` 由 `max-chunk-size` / `max-file-size` **+ 1 MB 余量**推导
    （multipart 边界/头部开销）；
  - 若 request、chunk、file 三层上限**全部无界**，**启动即失败**（`IllegalStateException`），
    不再默认不安全运行；设置任一上限即恢复正常启动。
- **验收**：完全不配置时启动推导出有界容器上限（10 MB 分片）；三层全无界启动失败且报错清晰；
  显式 `max-request-size` 不变；两条线（starter + 纯 servlet，javax + jakarta）镜像。
- **预估**：0.5 人天。

### T56 校验不可跳过（core + starter 两线，M1 / G30）

- **涉及文件**：`UploadFileProperties`（两条 starter 线；新增 `require-checksum`）、
  `UploadFileAutoConfiguration`（两条 starter 线；透传）、
  `ResumableUploadService`（校验路径）、测试。
- **方案**：新增 `upload-file.require-checksum`（默认 `false`，向后兼容）；当
  `verify-checksum + require-checksum` 同时启用时，缺失 `chunkMd5` 的分片**拒绝并删除**——
  校验不再能靠省略字段绕过。
- **验收**：两开关同时开启时无 `chunkMd5` 的分片失败（文档化 `400` 语义）且无残留；仅
  `verify-checksum` 时保持 rc.8 行为；两线各测。
- **预估**：0.5 人天。

### T57 下载路径 canonical 前缀校验（core，M2 / G31）

- **涉及文件**：`ResumableUploadService` → `ResumableDownloadService.resolveFile`（规范化 + 前缀校验）、
  `DownloadServletTest`（servlet 两线）、测试。
- **方案**：对合并目录与请求路径**都**做规范化（`getCanonicalFile()`）；解析出的文件必须**位于**
  合并目录**之内**；目录之外一律按**不存在**处理（404 语义）——经规范化逃逸的遍历无法读取合并目录
  之外的文件。
- **验收**：构造逃逸合并目录的 `finalPath` 按不存在处理；合法合并文件解析不变；合法路径的
  Range/下载行为与 rc.8 逐字节一致。
- **预估**：0.5 人天。

### T58 访问日志注入字符过滤（servlet 两线 + starter 两线，M3 / G32）

- **涉及文件**：`UploadFileContext`（两条纯 Servlet 线；访问日志写入）、
  `UploadFileAutoConfiguration` 的 access-log 监听器（两条 starter 线）、测试。
- **方案**：写入访问日志的每个值都经 `sanitizeLog()` 过滤，去除换行 / 回车 / 制表符及其它控制字符
  （CWE-117）；日志格式不变，仅对值消毒。
- **验收**：含 `\n`/`\r`/控制字符的 identifier/action 只产生一条消毒后的日志行；正常日志与 rc.8
  逐字节一致；四条装配路径镜像。
- **预估**：0.5 人天。

### T59 孤儿清理逐条隔离（core，M4 / G33）

- **涉及文件**：`StorageCleanupService.cleanupOrphans`（逐 identifier 隔离）、
  `StorageCleanupServiceTest`、`StorageCleanupService.hasTask()` 处理。
- **方案**：每条 identifier 的清理包在独立 `try-catch` 中，单条坏数据不再中断整个轮次；
  `hasTask()` 对**非法 identifier 名**按「无任务」处理（可按孤儿删除）；错误记日志后继续。
- **验收**：注入一条损坏 identifier 后，同轮次内其余 identifier 仍被清理；单条失败永不中断循环；
  正常清理行为不变。
- **预估**：0.5 人天。

### T60 `ChunkStorage` 带字节上限的流式重载 `saveChunk(..., maxBytes)`（core，M5 / G34）

- **涉及文件**：`upload-file-core/.../core/storage/ChunkStorage.java`（新增 `default` 重载）、
  `LocalFileChunkStorage`（覆写）、受影响的测试/mock。
- **方案**：
  - 新增 `default long saveChunk(String identifier, int chunkIndex, InputStream in, long maxBytes)`：
    **按实际字节计数**（经 `CountingInputStream`），超限**写入中途即中止**并清理部分分片，返回实际
    写入字节数；
  - 默认实现经计数流代理写入，因此**任何** `ChunkStorage` 实现无需改动即获得实际字节上限保证；
  - 既有 3 参 `saveChunk(String, int, InputStream)` 在 rc.9 期间维持现状（其返回类型改动在 GA 回退，
    见 §10）。
- **验收**：超过 `maxBytes` 的分片写入中途中止且无残留；返回计数等于磁盘实际字节；仅实现 3 参方法的
  自定义 `ChunkStorage` 经默认实现同样获得上限保证。
- **预估**：1 人天。

### T61 文档 / 测试 / 版本 / 发布（H1–M5 同步 + rc.9 发布）

- **涉及文件**：`CHANGELOG(.zh-CN).md`、`README(.zh-CN).md`、`docs/PLAN-V1.0.0(.zh-CN).md`（SOW §3
  rc.9 表 + GA 二进制兼容注记）、`docs/DESIGN(.zh-CN).md`（rc.9 安全机制）、`docs/API(.zh-CN).md`
  （`require-checksum` 的 400 语义）、`docs/ROADMAP(.zh-CN).md`（rc.9 条目 + GA 登记）、新增
  `security-fix-report/security-fix-report.html`（逐问题根因/修复/验证）、父 POM + 各模块 POM
  `1.0.0-rc.8 → 1.0.0-rc.9`（含 `upload-file-bom` 与三个 demo）、本地 `.m2` 经 `mvn install` 同步。
- **方案**：安全修复报告逐项记录 7 个问题的攻击路径、修复与验证拆解；README/SOW 记录
  breaking-default 提示（`max-chunk-size` 10 MB、启动失败、`require-checksum`）；SOW §3 表补充
  rc.9 属性面，§GA 注记记录 `saveChunk` 二进制兼容回退。
- **验收**：**580 单元测试 0 失败**；JDK 17+ 全 reactor `mvn verify` 全绿；中英文档同步。
- **预估**：0.5 人天。

## 四、新增配置与 SPI 面

| 类别 | 项 | 默认 | 是否 breaking |
| --- | --- | --- | --- |
| 属性 | `upload-file.require-checksum` | `false` | 否（新属性；additive） |
| 属性 | `upload-file.max-chunk-size` | `10 MB`（原 `-1`） | **是**（breaking-default：仅默认值；显式配置不变） |
| 行为 | request/chunk/file 三层上限全部无界 | **启动失败**（`IllegalStateException`） | **是**（fail-fast；设置任一上限即恢复启动） |
| 行为 | 未配置 `max-request-size` | 由 `max-chunk-size`/`max-file-size` 推导（+1 MB 余量） | 否（仅默认推导） |
| API | `ChunkStorage.saveChunk(String, int, InputStream, long maxBytes)`（新增 `default`，返回写入字节数） | 计数流代理 | 否（additive；3 参 `void` 在 GA 恢复，§10） |
| SPI | `StorageCleanupService.cleanupOrphans` | 逐条 `try-catch` 隔离 | 否（行为加固） |

## 五、兼容性（rc.8 → rc.9）

| 行为 | rc.8 | rc.9 | 说明 |
| --- | --- | --- | --- |
| 配额 / 大小限制 | 信任客户端声明值 | 按**磁盘实际字节**计数 | H1 修复；声明值仍用于前置校验 |
| `max-chunk-size` 默认 | `-1`（无上限） | `10 MB` | **breaking-default**；显式配置不变 |
| 三层上限全部未配置时启动 | 默认不安全运行 | **启动即失败** | H2 修复；设置任一上限即恢复 |
| MD5 校验 | 缺失 `chunkMd5` 跳过校验 | `require-checksum` 下拒绝并删除 | M1 修复；`require-checksum=false` 保持 rc.8 |
| 下载 `finalPath` | 未规范化前缀校验 | canonical 前缀校验；逃逸按不存在 | M2 修复 |
| 访问日志 | 原始拼接 | `sanitizeLog()`（CWE-117） | M3 修复；日志格式不变 |
| 孤儿清理 | 单条脏数据中断轮次 | 逐条隔离、轮次继续 | M4 修复 |
| 超限分片 | 先完整落盘再校验 | 写入中途中止、清理部分分片 | M5 修复；3 参 `void` 在 GA 恢复（§10） |
| 成功体 / 端点 / 磁盘与元数据格式 / 冻结 API 与属性键 | — | 不变 | — |

- 不新增产物；既有产物坐标与 `upload-file.*` 键不变；core 手工装配继续可用。
- 无磁盘布局 / 任务元数据格式变化；无 Redis 索引结构变化。

## 六、测试计划

- core：负值 `fileSize` 拒绝；保存后尺寸复核与超限删除；canonical 前缀逃逸用例按不存在处理；
  `sanitizeLog` 控制字符过滤；注入脏 identifier 的清理逐条隔离；字节上限 `saveChunk` 中止与清理。
- starter + servlet（javax + jakarta，互为镜像）：10 MB 默认推导、未配置 `max-request-size` 推导、
  三层无界启动失败；`require-checksum` 接受/拒绝；访问日志消毒。
- 兼容回归：默认 `task-store` 路径与成功体对 rc.8 逐字节断言不变；仅 `verify-checksum` 保持 rc.8 行为；
  显式 `max-request-size` 不变。
- 发布：JDK 17+ 全 reactor `mvn verify`——**580 单元测试 0 失败**；各模块保留 JaCoCo。

## 七、文档与示例更新

- 新增 `security-fix-report/security-fix-report.html`：7 个问题各自的攻击路径、修复（代码）与验证
  （580 测试拆解）。
- `CHANGELOG(.zh-CN).md`：rc.9 条目（7 项修复，2 高危 + 5 中危）+ breaking-default 提示。
- `README(.zh-CN).md`：rc.9 升级提示（`max-chunk-size` 10 MB 默认、启动失败、`require-checksum`）。
- `docs/API(.zh-CN).md`：`require-checksum=true` 且缺失 `chunkMd5` 时返回 `400`。
- `docs/DESIGN(.zh-CN).md`：rc.9 安全机制（实测字节计数、规范化校验、日志消毒、清理隔离、有界写入）；
  `saveChunk(maxBytes)` 扩展点。
- `docs/ROADMAP(.zh-CN).md` / `docs/PLAN-V1.0.0(.zh-CN).md`：rc.9 安全收口条目 → 发布后翻转；GA 登记；
  SOW §3 rc.9 表 + GA 二进制兼容注记。
- 新增 `docs/PLAN-V1.0.0-rc.9(.zh-CN).md`（本计划）。

## 八、里程碑与发布

1. **M1**（T54、T55）：H1/H2——按实际字节记账 + 默认收紧 / 启动失败（最高优先，GA 硬门槛）；
2. **M2**（T56、T57）：M1/M2——校验不可跳过 + 下载路径校验；
3. **M3**（T58、T59）：M3/M4——日志消毒 + 清理隔离；
4. **M4**（T60）：M5——有界流式写入；
5. **M5**（T61）：文档 / 安全修复报告 / 版本 `1.0.0-rc.8 → 1.0.0-rc.9` / 全量 `mvn verify`
   （580 测试）/ 发布两线 + BOM；
6. **M6（GA）**：`1.0.0-rc.9 → 1.0.0`，仅版本号与发布公告，外加 `saveChunk` 二进制兼容回退（§10）。

## 九、本版不纳入（GA 后 1.0.x / 1.1）

- **生态项（ROADMAP P2-5）**：Prometheus 指标、上传完成 Webhook、内容寻址秒传、整文件 SHA-256 校验、
  对象存储后端、tus 协议、病毒扫描钩子——按 ROADMAP 节奏推进。
- **单用户配额 / 限速、多租户命名空间、回收站 / 版本管理、预签名下载**——GA 后按需排期。
- **javax 线收敛**：自 GA 起维护态，2.0 移除（SOW 已声明策略）。

## 十、GA 衔接（rc.9 → 1.0.0）

rc.9 之后冻结面保持冻结：

- **仅 additive 与默认收紧**：rc.9 恰好新增一个属性（`require-checksum`）与一个 SPI 方法
  （`saveChunk(..., maxBytes)` `default`），外加两处受控默认变更（`max-chunk-size` 10 MB、
  三层无界 fail-fast）已记录于 §4/§5。无任何冻结 API、SPI 或属性键被删除或改变语义。
- **`1.0.0` 的唯一代码改动是二进制兼容回退**：rc.9 曾将 `ChunkStorage.saveChunk(String, int,
  InputStream)` 的返回类型改为 `long`；revapi 门禁（以 rc.7/rc.8 为基线）判定返回类型变更为
  **非 additive**——按 rc.7/rc.8 编译的自定义 `ChunkStorage` 实现会在字节码层失败。`1.0.0` 恢复
  3 参方法为 `void`（自 rc.7 冻结），字节计数改由 4 参 `default` 重载提供（计数流；`LocalFileChunkStorage`
  覆写），M5 的安全保证在任何实现下都成立且不破坏 SPI；受影响的测试 mock 随回退同步修正。
- **GA 准入**：revapi 对 rc.7/rc.8 基线全绿、`upload-file-bom` 发布可用、JDK 17+ 全 reactor
  `mvn verify` 全绿（rc.9 时 **580 测试 0 失败**）。
