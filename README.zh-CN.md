# upload-file

大文件**分片上传 / 断点续传 / Range 断点下载**的 Maven 工具包，纯 Java 编写，兼容 JDK 8 及以上。

| | |
| --- | --- |
| 坐标 | `cn.chenxinjie:upload-file:1.0.0-rc.5`（父 POM / 聚合器） |
| 最低运行环境 | JDK 8 |
| 运行依赖 | 仅 Gson（核心模块） |
| 模块 | `upload-file-core` · `upload-file-servlet` · `upload-file-servlet-jakarta` · `upload-file-spring-boot-starter` · `upload-file-spring-boot-starter-jakarta` · `upload-file-store-jdbc` · `upload-file-store-redis` · `example/upload-file-demo` · `example/upload-file-boot4-demo` · `example/upload-file-servlet-demo` |

> 🚧 状态：**Pre-release** `1.0.0-rc.5` — 正式版 `1.0.0` 发布前 API 可能调整。详见[更新日志](CHANGELOG.zh-CN.md)。

> 🇺🇸 [English](README.md)

## 特性

- 分片上传：大文件拆分为多个分片依次上传，失败只重传失败分片
- 断点续传：服务端记录「已上传分片」，客户端可随时暂停/继续
- 分片校验：可选校验每个分片 MD5，避免脏数据
- 分片合并：按序合并分片，校验最终文件大小，合并后自动清理分片
- 合并原子化：先写同目录临时文件，可选 fsync，再原子改名落位；中途失败不留坏文件
- 合并异步化：后台执行合并，通过 `mergeAsync` / `mergeStatus` 轮询状态
- 过期任务与孤儿清理：按 TTL 清理未完成任务，可选孤儿数据 GC
- 断点下载：基于 HTTP `Range` 的断点续传下载（`206 Partial Content`）
- 元数据持久化：进度可落盘 JSON，也可通过 `TaskStore` SPI 接入 JDBC / Redis
- 多接入方式：纯 Servlet / Spring Boot 自动配置 / 直接调用核心 API
- **访问控制**：可选共享令牌校验，作用于全部接口（`401`）；令牌可通过可配置请求头或 `token` 查询参数传递
- **大小与配额**：单文件总大小上限与可选全局容量配额（`400` / `507 Insufficient Storage`）
- **清理可观测**：每次清理输出结构化统计日志，并提供可查询的 `CleanupStats` 快照
- **任务存储迁移**：`TaskStoreMigrator` 可在存储间迁移进行中任务（如 `FileTaskStore` → JDBC/Redis）
- **元数据版本化**：`schemaVersion` 字段，保障元数据格式安全演进
- **多实例协调**：可选 Redis 租约锁，保证同一时刻只有一个实例执行清理调度
- **任务显式读取与取消（rc.4）**：`getTask(identifier)` 作为集成侧「confirm 入库」阶段的稳定读接口；`cancelUpload(identifier)` / HTTP `POST /upload?action=cancel` 可直接回收任务分片与合并产物，无需等待清理调度
- **稳定的错误语义（rc.4）**：core 失败异常统一携带 `UploadErrorCode` 稳定 HTTP 状态码（`400`/`401`/`404`/`409`/`507`），Servlet 与 Spring 集成自动映射

## 模块说明

| 模块 | 说明 | 引用方式 |
| --- | --- | --- |
| `upload-file-core` | 核心纯 Java 组件：模型、校验、存储 SPI、上传/下载/清理服务 | 任何 Java/Maven 项目 |
| `upload-file-servlet` | Servlet 3.0+（`javax.servlet`）接入：分片上传 Servlet、Range 下载 Servlet | Servlet 3/4 容器项目 |
| `upload-file-servlet-jakarta` | `upload-file-servlet` 的 Jakarta Servlet 5/6（`jakarta.servlet`）孪生版（FQCN 相同，无缝替换） | Tomcat 10/11、Boot 3/4 项目 |
| `upload-file-spring-boot-starter` | Spring Boot 2.x（`javax.servlet`）自动配置，零配置开箱即用 | Spring Boot 2.x 项目 |
| `upload-file-spring-boot-starter-jakarta` | starter 的 Spring Boot 3/4（`jakarta.servlet`）孪生版（`upload-file.*` 属性一致，无缝替换） | Spring Boot 4.0.0+ 项目（3.x 预期兼容） |
| `upload-file-store-jdbc` | 可选：JDBC 版 `TaskStore`（自动建表，H2 测试） | 配置 `metadata-store=jdbc` 时 |
| `upload-file-store-redis` | 可选：Redis 版 `TaskStore`（基于 Jedis） | 配置 `metadata-store=redis` 时 |
| `example/upload-file-demo` | 演示用例：Spring Boot 2 + 前端页面，展示完整断点续传流程 | — |
| `example/upload-file-boot4-demo` | 演示用例：Spring Boot 4（`jakarta`），使用 `upload-file-spring-boot-starter-jakarta` | — |
| `example/upload-file-servlet-demo` | 演示用例：纯 Servlet（无 Spring），通过 web.xml 装配 | — |

## 快速开始

### 方式一：Spring Boot 项目（推荐）

**Spring Boot 4.0.0+（或 3.x，即 jakarta 技术栈）**：使用 jakarta starter（运行期 JDK 17+）：

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter-jakarta</artifactId>
    <version>1.0.0-rc.5</version>
</dependency>
```

**Spring Boot 2.x（`javax.servlet`）**：使用原版 starter：

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0-rc.5</version>
</dependency>
```

两个 starter 共享相同的包名（`cn.chenxinjie.uploadfile.springboot.*`）、相同的 `upload-file.*` 属性与端点，
切换 Boot 世代只需更换 Maven 坐标。**切勿把 `javax` 产物与其 `-jakarta` 孪生版同时放入同一 classpath**——二选一。

配置 `application.yml`：

```yaml
upload-file:
  storage-dir: ./data/upload            # 分片与合并文件根目录
  metadata-dir: ./data/upload/meta      # 任务元数据落盘目录（留空则用内存）
  verify-checksum: true
```

启动后即可使用：

- `POST /upload` 上传分片
- `GET /upload?action=progress&identifier=xxx` 查询进度
- `POST /upload?action=merge&identifier=xxx` 合并
- `POST /upload?action=mergeAsync&identifier=xxx` 提交异步合并（HTTP `202`），用 `mergeStatus` 轮询
- `GET /upload?action=mergeStatus&identifier=xxx` 查询异步合并状态
- `POST /upload?action=cancel&identifier=xxx` 取消任务并回收其数据
- `GET /download?identifier=xxx` 下载（支持 `Range` 头断点续传）

> Boot 3/4 通过 `AutoConfiguration.imports`、Boot 2.x 通过 `spring.factories` 发现自动配置；servlet 层依据
> 所选产物面向 `javax.servlet`（Boot 2 / Servlet 3.1）或 `jakarta.servlet`（Boot 3/4 / Tomcat 10+）。

### 方式二：纯 Servlet 容器

依赖 `upload-file-servlet`（Servlet 3/4，`javax`）或 `upload-file-servlet-jakarta`（Servlet 5/6，
`jakarta`，如 Tomcat 10/11），通过注解扫描注册两个 Servlet（`/upload`、`/download`），
并可通过 init-param 指定存储目录。要求 Servlet 3.0+（下载超过 2GB 的区间内容需要 Servlet 3.1+）：

```xml
<servlet>
    <servlet-name>uploadFileServlet</servlet-name>
    <servlet-class>cn.chenxinjie.uploadfile.servlet.UploadServlet</servlet-class>
    <init-param><param-name>storage-dir</param-name><param-value>/data/upload</param-value></init-param>
    <init-param><param-name>metadata-dir</param-name><param-value>/data/upload/meta</param-value></init-param>
</servlet>
<servlet-mapping>
    <servlet-name>uploadFileServlet</servlet-name>
    <url-pattern>/upload</url-pattern>
</servlet-mapping>
```

### 方式三：只使用核心 API

依赖 `upload-file-core`，直接编程：

```java
TaskStore store = new FileTaskStore("/data/upload/meta");
ChunkStorage chunks = new LocalFileChunkStorage("/data/upload/chunks");
ResumableUploadService service = new ResumableUploadService(store, chunks, new File("/data/upload/files"));

// 上传分片
service.uploadChunk(request, chunkInputStream);
// 查询进度 / 合并
UploadProgress progress = service.getProgress(identifier);
UploadResult result = service.merge(identifier);
```

**confirm 入库阶段（rc.4）：** 通过合并结果或任务记录定位合并产物，切勿自行推导目录结构；完成后回收任务：

```java
UploadTask task = service.getTask(identifier).get();        // 稳定读接口
Path artifact = Paths.get(task.getFinalPath());             // 合并产物的权威路径
Files.move(artifact, businessDir.resolve(fileName));        // 迁入业务存储
service.cancelUpload(identifier);                           // 删除任务 + 残留数据
```

任务不存在时 `getTask` 返回 empty；`cancelUpload` 在任务不存在时返回 `false`，在异步合并
PENDING/RUNNING 期间抛出 `409`。

## 配置参考（Spring Boot）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `upload-file.storage-dir` | `./upload-file-data` | 分片与合并文件根目录 |
| `upload-file.metadata-dir` | *(空)* | 任务元数据目录；为空使用内存（重启后丢失） |
| `upload-file.metadata-store` | `auto` | `auto`（有 `metadata-dir` → file，否则 memory）/ `memory` / `file` / `jdbc` / `redis` |
| `upload-file.verify-checksum` | `true` | 是否校验分片 MD5 |
| `upload-file.upload-url` | `/upload` | 上传 Servlet 映射路径 |
| `upload-file.download-url` | `/download` | 下载 Servlet 映射路径 |
| `upload-file.max-chunk-size` | `-1` | 单个分片最大字节数：multipart 层与上传服务双重限制；`-1` 不限 |
| `upload-file.max-request-size` | `-1` | 单个请求最大字节数（multipart）；`-1` 不限 |
| `upload-file.merge.fsync` | `true` | 改名落位前是否 fsync 合并临时文件 |
| `upload-file.merge.atomic` | `true` | 是否采用「临时文件 + 原子改名」合并 |
| `upload-file.cleanup.enabled` | `false` | 是否启动过期任务/孤儿清理调度 |
| `upload-file.cleanup.run-on-startup` | `false` | 启动时是否先执行一次清理 |
| `upload-file.cleanup.interval` | `1h` | 清理周期 |
| `upload-file.cleanup.task-ttl` | `24h` | 未完成任务过期时间；`0` = 永不清理 |
| `upload-file.cleanup.orphan-enabled` | `false` | 是否开启孤儿数据清理（需持久化存储） |
| `upload-file.async-merge.enabled` | `false` | 是否开启异步合并 |
| `upload-file.async-merge.thread-pool-size` | `2` | 异步合并线程数 |
| `upload-file.jdbc.table-name` | `upload_task` | JDBC 表名 |
| `upload-file.jdbc.init-sql` | `CREATE TABLE IF NOT EXISTS %s (...)` | 自动建表 SQL；表名替换第一个 `%s` |
| `upload-file.redis.host` | `localhost` | Redis 主机 |
| `upload-file.redis.port` | `6379` | Redis 端口 |
| `upload-file.redis.password` | *(空)* | Redis 密码；空 = 无认证 |
| `upload-file.redis.key-prefix` | `upload:task:` | Redis key 前缀 |
| `upload-file.redis.ttl-seconds` | `0` | Redis 记录 TTL；`0` = 不过期 |
| `upload-file.security.enabled` | `false` | 是否启用访问控制（需配置令牌） |
| `upload-file.security.token` | *(空)* | 共享访问令牌；空 = 不校验 |
| `upload-file.security.header-name` | `X-Access-Token` | 令牌请求头名称（也接受 `token` 查询参数） |
| `upload-file.max-file-size` | `-1` | 单文件总大小上限（字节）；`-1` 不限 |
| `upload-file.quota.max-bytes` | `0` | 全局容量配额（字节）；`0` 关闭 |
| `upload-file.cleanup.use-redis-lock` | `false` | 使用 Redis 租约锁，保证单实例执行清理 |
| `upload-file.observability.log-stats` | `true` | 每次清理后输出结构化统计日志 |
| `upload-file.migration.enabled` | `false` | 暴露 `TaskStoreMigrator` Bean（迁移从不自动执行） |

上表中的点号名称对应嵌套分组，因此同样的配置也可以用分组 YAML 书写：

```yaml
upload-file:
  storage-dir: ./data/upload
  verify-checksum: true
  merge:
    fsync: true
    atomic: true
  cleanup:
    enabled: true
    interval: 1h
    task-ttl: 24h
    use-redis-lock: true
  async-merge:
    enabled: true
    thread-pool-size: 2
  security:
    enabled: true
    token: change-me
    header-name: X-Access-Token
  quota:
    max-bytes: 10737418240
  observability:
    log-stats: true
  jdbc:
    table-name: upload_task
  redis:
    host: localhost
    port: 6379
    key-prefix: upload:task:
```

> 纯 Servlet 部署使用同名 init-param 配置（如 `chunk.max-size`、`cleanup.enabled`、`async-merge.enabled`、
> `security.token`、`max-file-size`、`quota.max-bytes`）。

## 访问控制

当 `upload-file.security.enabled=true` 且已配置令牌时，所有接口都要求提供令牌：通过 `security.header-name`
指定的请求头（默认 `X-Access-Token`）或 `token` 查询参数传递。未携带有效令牌的请求返回 `401`。
开启安全校验而未配置令牌会在启动时直接失败（fail-fast），避免误配置导致接口静默开放。
安全关闭（默认）时行为与旧版本完全一致。

> **对接已有登录态：** 如需复用自有会话（Bearer/SSO）而非共享令牌，实现一次 `AccessControl` SPI 并在
> `check(...)` 中委托给当前登录主体即可——core 会在每个入口调用它。在 `/upload` 前置 Spring Security
> 过滤器同样可行（多数单组织私有部署的选择）；组件只在自身 SPI 被装配时才强制其鉴权。

## 集成指南（自 1.0.0-rc.4 起）

### 合并产物的定位（confirm 入库阶段）

合并产物的路径是**契约**：请从合并结果（`UploadResult.finalPath` / `MergeStatus.finalPath`）或任务记录
`getTask(identifier).getFinalPath()` 读取，不要自行推导 `{storage-dir}/files/{identifier}/{fileName}`。
典型流程：

```java
// 前端报告合并 SUCCEEDED / 同步 merge 返回后
UploadTask task = service.getTask(identifier).get();           // 稳定读接口（rc.4）
Path artifact = Paths.get(task.getFinalPath());                // 权威路径
Files.move(artifact, businessDir.resolve(task.getFileName())); // 同盘 => 原子移动
service.cancelUpload(identifier);                              // 回收记录 + 残留（rc.4）
```

`cancelUpload` 删除任务记录、其分片与合并产物目录；任务不存在时返回 `false`，异步合并 PENDING/RUNNING
期间抛出 `409`（等待其结束后重试）。

### 磁盘数据何时被回收

- **超期未完成任务**：`StorageCleanupService` 的 TTL 扫描会清理超过 `cleanup.task-ttl` 未更新的任务及其分片。
- **孤儿数据**：任务记录已消失（如 Redis 元数据 TTL 到期）但仍残留的分片/合并目录，由可选的孤儿清理
  （`cleanup.orphan-enabled: true`）回收。孤儿扫描不会针对内存 store 执行。
- **存续任务的「已合并未确认」产物会被保留**——它们仍是合法的下载对象，只能被显式 `cancelUpload`
  或在任务记录超期后回收。
- **建议**：接线清理调度 **并** 在 confirm 时调用 `cancelUpload`，避免上百 MB 的合并产物等 TTL。
  Starter：`cleanup.enabled: true`、`cleanup.orphan-enabled: true`；
  core 手工装配：构造与上传服务共享同一 `IdentifierLock` 的 `StorageCleanupService`，调用
  `cleanup()` / `start(intervalMillis)`。

### 手工装配（core）不消费 `upload-file.*` 属性

属性绑定、清理调度与异步合并线程池都只存在于 **Spring Boot starter** 中。当直接手工装配
`upload-file-core`（无 starter）时，以下项需**由调用方程序化配置**，仅写 `application.yml` 无效：
`cleanup.enabled/interval/task-ttl/orphan-enabled/use-redis-lock`、`async-merge.enabled/thread-pool-size`、
`max-request-size`、`security.enabled/token`，以及 multipart 限制。
异步合并仅在调用 `setAsyncExecutor(executor)` 后生效（不调用或传 `null` 即保持同步合并）；清理只有启动
其调度器才会执行。servlet 模块则通过 init-param 读取同名配置。

### 自有 HTTP 层中的稳定错误语义

描述「客户端可恢复失败」的 core 异常统一实现 `UploadErrorCode`：

| `UploadErrorCode` | HTTP | 抛出场景 |
| --- | --- | --- |
| `UploadValidationException`（`IllegalArgumentException` 子类） | `400` | 分片参数非法、元数据不一致、大小超限、合并时缺分片 |
| `ChecksumMismatchException` | `400` | 分片 MD5 不匹配 |
| `AccessDeniedException` | `401` | 访问控制拒绝 |
| `UploadTaskNotFoundException`（`NoSuchElementException` 子类） | `404` | 对不存在的任务执行 merge/submit |
| `UploadMergeConflictException`（`IllegalStateException` 子类） | `409` | 向已合并/合并中的任务传分片、合并期间取消 |
| `QuotaExceededException` | `507` | 超过全局 `quota.max-bytes` |
| 其余异常 | `500` | 服务端失败 |

带码异常是其 Java 泛型父类的子类，故既有的
`catch (IllegalArgumentException / NoSuchElementException / IllegalStateException)` 代码仍然生效。
Spring `@ExceptionHandler` 中：

```java
@ExceptionHandler
ResponseEntity<?> onUploadError(Exception e) {
    int status = e instanceof UploadErrorCode ? ((UploadErrorCode) e).getHttpStatusCode() : 500;
    return ResponseEntity.status(status).body(Map.of("code", status, "message", e.getMessage()));
}
```

官方 Servlet 已自动应用该映射并返回 JSON 响应体。

### 不接下载 Servlet 时的 Range 解析

core 的 `DownloadRange.parse(String)` 可解析单段/多段 `Range` 头并识别不可满足区间；集成方若自行
提供下载（例如 confirm 阶段已迁出组件的文件），可直接复用它而非重写 Range 逻辑。下载仍存于组件的
合并产物，仍建议走官方 `/download` 端点。

## HTTP API 概览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /upload`（multipart，文件字段名 `file`） | 上传一个分片。参数：`identifier`、`fileName`、`fileSize`、`chunkSize`、`chunkTotal`、`chunkIndex`、`chunkMd5`。返回进度 JSON |
| `GET /upload?action=progress&identifier=xxx` | 查询上传进度 |
| `POST /upload?action=merge&identifier=xxx` | 合并全部分片。返回结果 JSON |
| `POST /upload?action=mergeAsync&identifier=xxx` | 提交异步合并（`202`）；进行/完成时拒收新分片 |
| `GET /upload?action=mergeStatus&identifier=xxx` | 查询异步合并状态（`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`） |
| `POST /upload?action=cancel&identifier=xxx` | 取消任务并回收其分片/合并产物 |
| `GET /download?identifier=xxx` | 完整下载（`200`） |
| `GET /download?identifier=xxx` + `Range` 头 | 区间下载（`206` / `416`） |

错误响应带 JSON 体与稳定状态码：`400` 参数非法/大小超限/MD5 不匹配、`401` 访问被拒、`404` 任务不存在、
`409` 合并状态冲突（见上表）、`507` 配额超限、`416` Range 不可满足。

## 构建与测试

```bash
mvn install
```

- 要求 Maven 3.6.3+、JDK 8+；
- 编译目标 `--release 8`，产物为 JDK8 字节码，**JDK 8 可直接引用**；
- 由于使用了 `--release`，从源码构建需要 JDK 9+（若须在 JDK 8 工具链上构建，移除父 POM 中的 `maven.compiler.release` 即可）；
- 自 `1.0.0-rc.5` 起 reactor 含 jakarta 模块（`upload-file-servlet-jakarta`、
  `upload-file-spring-boot-starter-jakarta`、`example/upload-file-boot4-demo`），其 Spring Boot 4 / Servlet 6
  依赖需要 **JDK 17+** 工具链，因此根目录全量 `mvn verify` 需在 JDK 17+ 上执行；在 JDK 8 工具链上仅构建
  `javax` 线请用子集构建，如 `mvn install -pl upload-file-core,upload-file-servlet,upload-file-spring-boot-starter -am`。

## 运行 Demo

**Spring Boot 4 Demo**（`example/upload-file-boot4-demo`，使用 `upload-file-spring-boot-starter-jakarta`）：

```bash
mvn -pl example/upload-file-boot4-demo spring-boot:run
```

浏览器访问 <http://localhost:8080/>，在真实 Boot 4（`jakarta`）运行时上体验分片上传、暂停续传、
异步合并与断点续传下载。

**Spring Boot 2 Demo**（`example/upload-file-demo`）：

```bash
mvn -pl example/upload-file-demo spring-boot:run
# 或
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0-rc.5.jar
```

浏览器访问 <http://localhost:8080/>，选择一个文件体验分片上传、暂停续传、
合并与断点续传下载。

**纯 Servlet Demo**（`example/upload-file-servlet-demo`，无 Spring，通过 web.xml 装配）：

```bash
mvn -pl example/upload-file-servlet-demo jetty:run
```

访问 <http://localhost:8080/> 使用同一前端页面，直接调用 `UploadServlet` / `DownloadServlet`，
存储目录通过 web.xml 中的 `storage-dir` / `metadata-dir` init-param 指定。

## 安全

- `identifier` 与 `fileName` 均做校验，防止路径穿越（每个存储实现内部均校验）
- 可选的分片 MD5 校验
- 分片与元数据采用「临时文件 + 原子改名」写盘；合并同样原子化
- `max-chunk-size` / `chunk.max-size` 上限拒绝超限分片（防磁盘耗尽）
- `max-file-size` 单文件上限与可选 `quota.max-bytes` 全局配额，超限文件在落盘前即被拒绝
- 可选共享令牌访问控制（`security.*`），常量时间比较；默认关闭
- 分片元数据跨分片一致性校验，与首片不一致的后续分片被拒绝
- 清理与上传/合并共享按 identifier 的分片锁，后台 GC 不会与实时数据竞争
- 可选 Redis 清理租约锁，避免多实例重复执行清理

## 文档

- [架构设计](docs/DESIGN.zh-CN.md)
- [未来优化方向](docs/ROADMAP.zh-CN.md)
- [HTTP API 参考](docs/API.zh-CN.md)
- [更新日志](CHANGELOG.zh-CN.md)
- [V1.0.0-rc.5 任务开发计划（Spring Boot 4 / jakarta starter）](docs/PLAN-V1.0.0-rc.5.zh-CN.md)
- [V1.0.0-rc.4 任务开发计划（反馈驱动集成优化）](docs/PLAN-V1.0.0-rc.4.zh-CN.md)
- [V1.0.0-rc.3 任务开发计划（生产就绪加固）](docs/PLAN-V1.0.0-rc.3.zh-CN.md)

## 许可证

[MIT](LICENSE)
