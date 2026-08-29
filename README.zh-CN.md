# upload-file

大文件**分片上传 / 断点续传 / Range 断点下载**的 Maven 工具包，纯 Java 编写，兼容 JDK 8 及以上。

| | |
| --- | --- |
| 坐标 | `cn.chenxinjie:upload-file:1.0.0-rc.3`（父 POM / 聚合器） |
| 最低运行环境 | JDK 8 |
| 运行依赖 | 仅 Gson（核心模块） |
| 模块 | `upload-file-core` · `upload-file-servlet` · `upload-file-spring-boot-starter` · `upload-file-store-jdbc` · `upload-file-store-redis` · `example/upload-file-demo` · `example/upload-file-servlet-demo` |

> 🚧 状态：**Pre-release** `1.0.0-rc.3` — 正式版 `1.0.0` 发布前 API 可能调整。详见[更新日志](CHANGELOG.zh-CN.md)。

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

## 模块说明

| 模块 | 说明 | 引用方式 |
| --- | --- | --- |
| `upload-file-core` | 核心纯 Java 组件：模型、校验、存储 SPI、上传/下载/清理服务 | 任何 Java/Maven 项目 |
| `upload-file-servlet` | Servlet 3.0+ 接入：分片上传 Servlet、Range 下载 Servlet | Servlet 容器项目 |
| `upload-file-spring-boot-starter` | Spring Boot 2.x 自动配置，零配置开箱即用 | Spring Boot 项目 |
| `upload-file-store-jdbc` | 可选：JDBC 版 `TaskStore`（自动建表，H2 测试） | 配置 `metadata-store=jdbc` 时 |
| `upload-file-store-redis` | 可选：Redis 版 `TaskStore`（基于 Jedis） | 配置 `metadata-store=redis` 时 |
| `example/upload-file-demo` | 演示用例：Spring Boot + 前端页面，展示完整断点续传流程 | — |
| `example/upload-file-servlet-demo` | 演示用例：纯 Servlet（无 Spring），通过 web.xml 装配 | — |

## 快速开始

### 方式一：Spring Boot 项目（推荐）

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0-rc.3</version>
</dependency>
```

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
- `GET /download?identifier=xxx` 下载（支持 `Range` 头断点续传）

### 方式二：纯 Servlet 容器

依赖 `upload-file-servlet`，通过注解扫描注册两个 Servlet（`/upload`、`/download`），
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

## HTTP API 概览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /upload`（multipart，文件字段名 `file`） | 上传一个分片。参数：`identifier`、`fileName`、`fileSize`、`chunkSize`、`chunkTotal`、`chunkIndex`、`chunkMd5`。返回进度 JSON |
| `GET /upload?action=progress&identifier=xxx` | 查询上传进度 |
| `POST /upload?action=merge&identifier=xxx` | 合并全部分片。返回结果 JSON |
| `POST /upload?action=mergeAsync&identifier=xxx` | 提交异步合并（`202`）；进行/完成时拒收新分片 |
| `GET /upload?action=mergeStatus&identifier=xxx` | 查询异步合并状态（`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`） |
| `GET /download?identifier=xxx` | 完整下载（`200`） |
| `GET /download?identifier=xxx` + `Range` 头 | 区间下载（`206` / `416`） |

常见错误：`400` 参数非法 / 超过 `max-file-size`、`401` 访问被拒（启用访问控制时）、
`404` 文件不存在、`507 Insufficient Storage` 超过 `quota.max-bytes`、`416` Range 不可满足。

## 构建与测试

```bash
mvn install
```

- 要求 Maven 3.6.3+、JDK 8+；
- 编译目标 `--release 8`，产物为 JDK8 字节码，**JDK 8 可直接引用**；
- 由于使用了 `--release`，从源码构建需要 JDK 9+（若须在 JDK 8 工具链上构建，移除父 POM 中的 `maven.compiler.release` 即可）。

## 运行 Demo

**Spring Boot Demo**（`example/upload-file-demo`）：

```bash
mvn -pl example/upload-file-demo spring-boot:run
# 或
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0-rc.3.jar
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
- [V1.0.0-rc.3 任务开发计划（生产就绪加固）](docs/PLAN-V1.0.0-rc.3.zh-CN.md)

## 许可证

[MIT](LICENSE)
