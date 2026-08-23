# upload-file

大文件**分片上传 / 断点续传 / Range 断点下载**的 Maven 工具包，纯 Java 编写，兼容 JDK 8 及以上。

| | |
| --- | --- |
| 坐标 | `cn.chenxinjie:upload-file:1.0.0-rc.1`（父 POM / 聚合器） |
| 最低运行环境 | JDK 8 |
| 运行依赖 | 仅 Gson（核心模块） |
| 模块 | `upload-file-core` · `upload-file-servlet` · `upload-file-spring-boot-starter` · `upload-file-demo` |

> 🚧 状态：**Pre-release** `1.0.0-rc.1` — 正式版 `1.0.0` 发布前 API 可能调整。详见[更新日志](CHANGELOG.zh-CN.md)。

> 🇺🇸 [English](README.md)

## 特性

- 分片上传：大文件拆分为多个分片依次上传，失败只重传失败分片
- 断点续传：服务端记录「已上传分片」，客户端可随时暂停/继续
- 分片校验：可选校验每个分片 MD5，避免脏数据
- 分片合并：按序合并分片，校验最终文件大小，合并后自动清理分片
- 断点下载：基于 HTTP `Range` 的断点续传下载（`206 Partial Content`）
- 元数据持久化：任务进度可落盘（JSON），服务重启不丢任务
- 多接入方式：纯 Servlet / Spring Boot 自动配置 / 直接调用核心 API

## 模块说明

| 模块 | 说明 | 引用方式 |
| --- | --- | --- |
| `upload-file-core` | 核心纯 Java 组件：模型、校验、存储 SPI、上传/下载服务 | 任何 Java/Maven 项目 |
| `upload-file-servlet` | Servlet 3.0+ 接入：分片上传 Servlet、Range 下载 Servlet | Servlet 容器项目 |
| `upload-file-spring-boot-starter` | Spring Boot 2.x 自动配置，零配置开箱即用 | Spring Boot 项目 |
| `example/upload-file-demo` | 演示用例：Spring Boot + 前端页面，展示完整断点续传流程 | — |

## 快速开始

### 方式一：Spring Boot 项目（推荐）

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0-rc.1</version>
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
| `upload-file.verify-checksum` | `true` | 是否校验分片 MD5 |
| `upload-file.upload-url` | `/upload` | 上传 Servlet 映射路径 |
| `upload-file.download-url` | `/download` | 下载 Servlet 映射路径 |
| `upload-file.max-chunk-size` | `-1` | 单个分片最大字节数（multipart）；`-1` 不限 |
| `upload-file.max-request-size` | `-1` | 单个请求最大字节数（multipart）；`-1` 不限 |

## HTTP API 概览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /upload`（multipart，文件字段名 `file`） | 上传一个分片。参数：`identifier`、`fileName`、`fileSize`、`chunkSize`、`chunkTotal`、`chunkIndex`、`chunkMd5`。返回进度 JSON |
| `GET /upload?action=progress&identifier=xxx` | 查询上传进度 |
| `POST /upload?action=merge&identifier=xxx` | 合并全部分片。返回结果 JSON |
| `GET /download?identifier=xxx` | 完整下载（`200`） |
| `GET /download?identifier=xxx` + `Range` 头 | 区间下载（`206` / `416`） |

## 构建与测试

```bash
mvn install
```

- 要求 Maven 3.6.3+、JDK 8+；
- 编译目标 `--release 8`，产物为 JDK8 字节码，**JDK 8 可直接引用**；
- 由于使用了 `--release`，从源码构建需要 JDK 9+（若须在 JDK 8 工具链上构建，移除父 POM 中的 `maven.compiler.release` 即可）。

## 运行 Demo

```bash
mvn -pl example/upload-file-demo spring-boot:run
# 或
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0-rc.1.jar
```

浏览器访问 <http://localhost:8080/>，选择一个文件体验分片上传、暂停续传、
合并与断点续传下载。

## 安全

- `identifier` 与 `fileName` 均做校验，防止路径穿越
- 可选的分片 MD5 校验
- 分片与元数据采用「临时文件 + 原子改名」写盘

## 文档

- [架构设计](docs/DESIGN.zh-CN.md)
- [HTTP API 参考](docs/API.zh-CN.md)
- [更新日志](CHANGELOG.zh-CN.md)

## 许可证

[MIT](LICENSE)
