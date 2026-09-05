# V1.0.0-rc.5 任务开发计划（Spring Boot 4 / jakarta Starter）

> 🇺🇸 [English](PLAN-V1.0.0-rc.5.md)
>
> 关闭消费方反馈（`doc/user-feedback/upload-file-usage-feedback.md`，**P0-1**）中 rc.4 顺延的最大集成阻塞项：
> 提供官方 **jakarta** servlet 模块与 Spring Boot **4.0.0+** 自动配置 starter。当前官方
> `upload-file-servlet` / `upload-file-spring-boot-starter` 产物依赖 `javax.servlet`，因此 Spring Boot 3/4 使用方
> （如运行于 JDK 26 / Spring Boot 4.1.1 的 path-finder）无法使用，被迫手工装配 core。rc.5 发布
> `-jakarta` 孪生产物，做到源码级无缝替换。
>
> ✅ **状态：已在 `1.0.0-rc.5` 实现并发布。** T19–T24 全部完成：`upload-file-servlet-jakarta` /
> `upload-file-spring-boot-starter-jakarta` 孪生产物与 `example/upload-file-boot4-demo` 均已随 `1.0.0-rc.5`
> 发布，javax 产物保持不变——见[更新日志](../CHANGELOG.zh-CN.md)。

## 一、目标与范围

rc.4 文档化了清理/手工装配语义，但根因仍在：servlet 与 starter 产物基于 `javax.servlet`。rc.5 移除该根因。

| 编号 | 缺口 | 说明 | rc.5 任务 |
| --- | --- | --- | --- |
| G1 | 官方 servlet 产物基于 `javax.servlet` | `UploadFileContext` / `UploadServlet` / `DownloadServlet` 仅能编译于 Servlet 3/4（`javax`）；Servlet 5/6 容器（Tomcat 10/11，Boot 3/4）拒绝 | T20 |
| G2 | Boot starter 基于 `javax.servlet` | `UploadFileAutoConfiguration` 引用 `javax.servlet` + `MultipartConfigElement` 与 `@ConditionalOnClass(name={"javax.servlet.*"})`；经 Boot 2 风格 `META-INF/spring.factories` 注册，Boot 3/4 忽略 | T21、T22 |
| G3 | 无 Spring Boot 4 产物与验证基线 | 无面向 Spring Boot 4.0.0+ 的已发布坐标；无 Boot 4 示例与 CI 证明 | T23、T24 |
| G4 | 构建不支持两代 Servlet | 父 POM 仅一个 BOM（Boot 2.7）+ 单一 JDK 基线（`[8,)`）；jakarta 产物需要 Boot 4 BOM 与 JDK ≥ 17 构建 | T19 |
| G5 | 文档仅描述 javax 坐标 | README / API / DESIGN / demo POM 全部引用 javax 产物；无 javax↔jakarta 对照与 Boot 4 快速开始 | T24 |

## 二、架构与约束

新产物是**并行孪生**，而非替换既有坐标（既有 Boot 2 / Servlet 3.1 使用方依赖与行为不变）：

```
upload-file (父 POM / 聚合器，版本 1.0.0-rc.5)
├── upload-file-core                        # 不变，纯 Java（JDK 8）——两代共享
├── upload-file-store-jdbc / -redis         # 不变——两代共享（无 servlet 依赖）
├── upload-file-servlet          [javax]    # 不变（Boot 2 / Servlet 3.1，维护态）
├── upload-file-servlet-jakarta [新增]      # jakarta 移植（T20）
├── upload-file-spring-boot-starter         [javax]   # 不变（Boot 2，维护态）
├── upload-file-spring-boot-starter-jakarta [新增]    # Boot 3/4 jakarta starter（T21/T22）
├── example/upload-file-demo                # 不变（Boot 2 javax demo）
├── example/upload-file-boot4-demo [新增]   # Boot 4.0.0+ jakarta demo（T23）
└── example/upload-file-servlet-demo        # javax demo（不变）
```

约束与决策：
- **jakarta 孪生产物沿用相同 FQCN**（`cn.chenxinjie.uploadfile.servlet.*`、`cn.chenxinjie.uploadfile.springboot.*`）：
  使用方只换 Maven 坐标，不改任何代码/import。推论：javax 与 jakarta 产物**不可同处一个 classpath**（二选一，
  由文档约束）。
- **源码复制刻意且最小**：仅 servlet 层（3 个类）与 starter（自动配置 + 属性类）的 servlet import 不同；
  业务逻辑全部留在 `upload-file-core` 原样复用。
- **Boot 版本隔离**：jakarta starter/demo 在**模块级**导入 Spring Boot 4 BOM（`spring-boot-dependencies`，
  属性如 `spring.boot4.version`），取代父 POM 的 Boot 2.7 import，使 javax 与 jakarta 模块在同一 reactor 内
  针对不同 Spring/Servlet 代编译。
- **JDK 基线**：读取 Servlet 6 / Boot 4 类文件需 JDK ≥ 17 工具链，故根 `mvn verify` 事实上要求 JDK 17+。
  所有产物仍保持字节码 `--release 8`（javax 线的 JDK 8 使用方用 `-pl upload-file-core,upload-file-servlet,...`
  构建所需子集）。Maven enforcer 维持 `[8,)`，但 README 需写明全 reactor 的 JDK 17 构建要求。
- **新产物发布** Maven Central，与既有产物同 `1.0.0-rc.5` 版本（父 `dependencyManagement` + 各新模块
  `central-publishing-maven-plugin`）。
- **Boot 4.0.0+ 为验证/支持目标**；jakarta 产物预期亦可在 Boot 3.x（同为 `jakarta.servlet`）运行，
  但 rc.5 仅在 Boot 4.x 做 CI 验证。

## 三、任务拆解

### T19 构建拓扑：jakarta 模块、BOM 隔离、发布（G4）

- **涉及文件**：`pom.xml`（父）、新模块 POM、`.flattened-pom.xml` 重新生成。
- **方案**：
  - 在父 `<modules>` 与 `<dependencyManagement>`（同 `${project.version}`）注册 `upload-file-servlet-jakarta`、
    `upload-file-spring-boot-starter-jakarta`、`example/upload-file-boot4-demo`；
  - jakarta 模块与 Boot 4 demo 在本地导入 `spring-boot-dependencies`（独立属性 `spring.boot4.version`）；
    servlet-jakarta 模块将 `javax.servlet-api` 依赖替换为 `jakarta.servlet:jakarta.servlet-api`（provided）；
  - 保持字节码 `--release 8`；文档化 JDK 17 构建基线与 JDK 8 专属构建的 `-pl` 用法；
  - 两个待发布的 jakarta 模块补 `central-publishing-maven-plugin`。
- **验收**：JDK 17+ 下 `mvn -q clean install` 全绿（javax + jakarta 两线）；新产物以 `1.0.0-rc.5` 解析；
  JDK 8 子集（`-pl`）仍可编译 javax 模块；JDK 8 根构建失败时给出清晰的 javac "bad class file version" 报错，
  README 有指引。
- **预估**：1.5 人天。

### T20 `upload-file-servlet-jakarta`：servlet 层 jakarta 移植（G1）

- **涉及文件**：镜像 `upload-file-servlet` 的新模块；移植源 `UploadFileContext`、`UploadServlet`、
  `DownloadServlet`；移植测试 `UploadServletTest`、`DownloadServletTest`、`UploadFileContextTest`。
- **方案**：复制 3 个类与 3 个测试，把 servlet import 改为 `jakarta.servlet.*`
  （`HttpServlet`、`Part`、`@WebServlet`、`@MultipartConfig`、`ServletContext`/`ServletConfig` 等），逻辑零改动，
  FQCN 保持一致以便无缝替换。测试依赖 `spring-test` 切换到 Boot 4 / Spring 7 线，使
  `org.springframework.mock.web.*` mock 基于 jakarta（mock 类包路径未变，测试 import 不变）。
  `javax.servlet-api` → `jakarta.servlet-api`（provided）。
- **验收**：3 个移植测试在 `jakarta.servlet` mock 下全绿，行为与 javax 孪生一致；`action=cancel`、
  `UploadErrorCode` 映射（400/401/404/409/507）、Range 下载与 init-param 解析行为相同。
- **预估**：1 人天。

### T21 `upload-file-spring-boot-starter-jakarta`：Boot 4 自动配置（G2）

- **涉及文件**：镜像 `upload-file-spring-boot-starter` 的新模块；移植 `UploadFileAutoConfiguration`、
  `UploadFileProperties`；新增 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- **方案**：
  - servlet import `javax.*` → `jakarta.*`；`@ConditionalOnClass(name = {"jakarta.servlet.Servlet",
    "jakarta.servlet.MultipartConfigElement"})`；`MultipartConfigElement` 取自 `jakarta.servlet`；
  - 配置类标注 `@AutoConfiguration`（Boot 3+）并经由 `AutoConfiguration.imports` 文件注册，取代 `spring.factories`
    （Boot 3/4 忽略旧机制）；删除 `spring.factories`；
  - `UploadFileProperties` 不变（同 `upload-file.*` 前缀、默认值、嵌套组、configuration-processor 元数据），
    使用方配置零迁移；
  - 编译针对 Boot 4 `spring-boot-autoconfigure` / `spring-boot` / `spring-boot-configuration-processor`
    （provided/optional）；测试用 Boot 4 线 `spring-boot-starter-test` + `spring-boot-starter-web`。
- **验收**：类仅引用 jakarta + Boot 4 稳定 API；`spring-configuration-metadata.json` 列出的属性名与 javax
  starter 一致；Boot 4 classpath 下经 imports 文件发现自动配置。
- **预估**：1 人天。

### T22 jakarta starter 行为对齐（G2）

- **涉及文件**：移植的 starter 测试套件 + 移植自动配置的必要修正。
- **方案**：完整镜像 javax starter 测试套件（默认关闭/off-by-default、存储选择 `auto|memory|file|jdbc|redis`
  含缺 DataSource/缺类库回退、清理调度 + 启动扫描、异步合并执行器 + 状态机、访问控制缺 token 快速失败、
  migration bean 门控、`/upload` `/download` servlet 注册含 `action=cancel` 对齐、经装配的
  `ResumableUploadService` 的 `getTask`/`cancelUpload`）。断言无缝替换等价性：javax starter 装配的每个 Bean、
  属性、默认值，jakarta starter 均一致装配。
- **验收**：jakarta 模块测试在 Boot 4 全绿；属性集与 Bean 图等价（由移植测试验证而非仅代码比对）；
  自定义 Bean 仍可经 `@ConditionalOnMissingBean` 覆盖。
- **预估**：1.5 人天。

### T23 Spring Boot 4.0.0+ 示例 / POC（G3）

- **涉及文件**：新增 `example/upload-file-boot4-demo`（Boot 4 BOM、JDK ≥ 17、内嵌 Tomcat），前端页复用 Boot 2
  demo；README 走查。
- **方案**：demo 基于 jakarta starter，在真实 Boot 4.0.0+ 运行时验证全流程：分片上传 → 进度/续传 →
  `mergeAsync`/`mergeStatus` → 经 `getTask(...).getFinalPath()` confirm → Range 下载；覆盖 `action=cancel` 与
  `UploadErrorCode` 状态码；一次 `metadata-store=file`、一次可选 Docker `metadata-store=redis`
  （复刻 path-finder 场景；demo 不发布）。
- **验收**：JDK 17+ 下 `mvn -pl example/upload-file-boot4-demo spring-boot:run` 提供 `/upload`、`/download`
  与前端；手动走查在 Boot 4.0.0+ 完成；demo 唯一 upload-file 依赖为 jakarta starter。
- **预估**：1.5 人天。

### T24 文档与 javax↔jakarta 兼容矩阵（G5）

- **涉及文件**：`README(.zh-CN).md`、`docs/API(.zh-CN).md`、`docs/DESIGN(.zh-CN).md`、
  `docs/ROADMAP(.zh-CN).md`、`CHANGELOG(.zh-CN).md`。
- **方案**：
  - 产物矩阵：`javax` 线（Boot 2 / Servlet 3.1）vs `jakarta` 线（Boot 3/4），给出坐标与「二选一、不可同存」；
  - Boot 4.0.0+ 快速开始：Maven 依赖、`application.yml` 示例、JDK 17+ 要求、属性表引用；
  - 说明 core 手工装配（rc.4 记录的 path-finder 配方）在 Boot 4 依然有效，新 starter 使其可选；
  - 升级指南：Boot 2 → Boot 4（javax → jakarta）坐标替换；留在 Boot 2 的使用方继续走 javax 线；
  - 更新 DESIGN 模块图与 README 中「根 `mvn verify` 需 JDK 17+、javax 线经 `-pl` 仍可在 JDK 8 构建」的说明。
- **验收**：Boot 4 使用方读 README 即知用哪个坐标；矩阵与升级步骤完整；CHANGELOG 标记 jakarta 顺延项在 rc.5
  关闭。
- **预估**：1 人天。

## 四、新增 Maven 产物与构建基线

| 产物 | Servlet 命名空间 | 编译期依赖 | 支持运行环境 | 无缝替换 |
| --- | --- | --- | --- | --- |
| `upload-file-servlet-jakarta` | `jakarta.servlet` | `jakarta.servlet-api`（provided）、core | Servlet 5/6 容器、Boot 3/4 内嵌 Tomcat | `upload-file-servlet` |
| `upload-file-spring-boot-starter-jakarta` | `jakarta.servlet` | Boot 4 `spring-boot(-autoconfigure)`（provided）、servlet-jakarta、core、stores（optional） | Spring Boot 4.0.0+（3.x 预期兼容） | `upload-file-spring-boot-starter` |

- rc.5 **不新增任何 `upload-file.*` 配置项**——jakarta starter 沿用 javax starter 完全一致的属性集与默认值。
- 构建基线：根 `mvn verify` / 发布需 JDK 17+；各产物字节码保持 `--release 8`；JDK 8 使用方用 `-pl` 构建
  javax 子集。

## 五、兼容性

- 既有 `upload-file-servlet` 与 `upload-file-spring-boot-starter`（javax）**不改动**——Boot 2 / Servlet 3.1
  使用方坐标与行为不变；
- jakarta 孪生产物为**源码无缝替换**（相同 FQCN、相同属性）：迁移即换坐标；javax 与 jakarta 产物不可同存于
  一个 classpath；
- core 与两个 store 模块为共享、无 servlet 依赖、不变——Boot 4 上的手工 core 装配继续可用；
- 无 SPI / core API 变更；rc.5 仅打包层面增量。

## 六、测试计划

- 移植 servlet 测试（3）+ 完整 starter 测试套件至 jakarta 模块；JDK 17+ 下 Boot 4 / jakarta mock 全绿；
- `example/upload-file-boot4-demo` 手动 E2E（分片 → 续传 → 异步合并 → `finalPath` confirm → 下载；
  另覆盖 `cancel` 与 `UploadErrorCode` 400/404/409/401/507）；
- javax 模块回归重跑；JDK 17+ 全 reactor `mvn verify` 全绿（各模块保留 JaCoCo 报告）；
- 发布命令在 JDK 17+ 下使用既有 `release` profile（gpg），将两个新产物发布到 Maven Central。

## 七、文档与示例更新

- 新增 `example/upload-file-boot4-demo`；Boot 2 / javax demo 不动；
- `README(.zh-CN).md`：产物矩阵、Boot 4.0.0+ 快速开始、JDK 17 构建说明、坐标替换升级指南；
- `docs/API(.zh-CN).md` / `docs/DESIGN(.zh-CN).md`：jakarta 模块职责与依赖边；
- `docs/ROADMAP(.zh-CN).md`：标记 jakarta 适配（反馈 P0-1）为 rc.5 计划；
- `CHANGELOG(.zh-CN).md`：rc.5 条目关闭 jakarta 顺延项。

## 八、里程碑与发布

1. **M1**：T19——构建拓扑、BOM 隔离、模块注册并可发布；
2. **M2**：T20 + T21——servlet-jakarta 与 starter-jakarta 编译、测试全绿；
3. **M3**：T22——Boot 4 行为对齐套件；
4. **M4**：T23 + T24——Boot 4 demo/E2E 与文档矩阵；
5. **M5**：版本号 `1.0.0-rc.4 → 1.0.0-rc.5`，JDK 17+ 全量 `mvn verify`，按既有 release profile 发布 Maven
   Central；发布公告以 Boot 4.0.0+ 支持为亮点。
