# 架构设计

## 模块依赖

```
upload-file (父 POM / 聚合器)
├── upload-file-core                        纯 Java，无框架依赖
│     └── upload-file-servlet               Servlet 3.0+ 接入层
│           └── upload-file-spring-boot-starter   Spring Boot 自动配置
│                 └── example/upload-file-demo    演示用例
```

## 核心概念

- **identifier**：文件唯一标识，约定为整个文件的 MD5。服务端所有分片、任务记录、
  合并文件均以它为目录/主键，客户端据此实现断点续传（幂等）。
- **分片（chunk）**：文件按固定大小切分后的数据块，序号从 0 开始。
- **上传任务（UploadTask）**：记录 identifier、文件名、大小、分片数以及
  「已上传分片序号集合」的元数据，持久化于 `TaskStore`。

## 组件职责

| 组件 | 职责 |
| --- | --- |
| `TaskStore`（SPI） | 上传任务元数据读写。内置 `MemoryTaskStore`、`FileTaskStore` |
| `ChunkStorage`（SPI） | 分片物理存储。内置 `LocalFileChunkStorage` |
| `ResumableUploadService` | 分片保存、进度记录、MD5 校验、分片合并与清理 |
| `ResumableDownloadService` | 定位合并文件，按 `Range` 区间读取 |
| `UploadServlet` / `DownloadServlet` | HTTP 接入，解析 multipart / Range |
| `UploadFileAutoConfiguration` | Spring Boot 自动装配并注册 Servlet |

## 存储目录布局

`FileTaskStore` / `LocalFileChunkStorage` 采用如下目录结构：

```
<storage-dir>/
├── chunks/<identifier>/<index>.part   # 分片（临时文件 + 原子改名写入）
├── files/<identifier>/<fileName>      # 合并后的完整文件
└── (metadata-dir)/
    └── <identifier>.json              # 任务元数据（临时文件 + 原子改名写入）
```

`identifier` 经 `Strings.requireSafeIdentifier` 校验，禁止 `/`、`\`、`.`、`..`；
`fileName` 经 `Strings.requireSafeFileName` 校验（合并写盘前再次校验），防止路径穿越。
`FileTaskStore` / `LocalFileChunkStorage` 两个存储实现内部也会重复校验 identifier，防御直接调用 SPI 的调用方。

## 分片上传流程

```
客户端                                         服务端
   │  计算整个文件 MD5 → identifier               │
   │  依次上传每个分片(multipart)                 │
   │ ───────────────────────────────────────▶ ResumableUploadService
   │                                            │ 1. 查询/创建 UploadTask
   │                                            │ 2. 已上传过？→ 跳过（幂等）
   │                                            │ 3. ChunkStorage.saveChunk（原子改名）
   │                                            │ 4. 可选：校验分片 MD5
   │                                            │ 5. markUploaded + TaskStore.save
   │ ◀─────────────────────────────────────── 返回 UploadProgress(JSON)
   │  全部分片上传完成后调用 merge                │
   │ ───────────────────────────────────────▶ 按序合并 → files/<id>/<fileName>
   │                                            │ 校验最终文件大小 → 删除分片 → 标记 merged
```

## 断点续传下载流程

```
客户端                                    服务端
   │  GET /download?identifier=xxx          │
   │  Range: bytes=0-5999999                │
   │ ───────────────────────────────────▶   │ 解析 Range → 206 + Content-Range
   │  （网络中断）                           │
   │  GET /download  Range: bytes=6000000-  │ 从偏移处继续 → 206
   │ ───────────────────────────────────▶   │
```

不可满足的 Range 返回 `416`，并在 `Content-Range` 中返回 `bytes */<size>`。

## 扩展点

- **更换任务存储**：实现 `TaskStore`，接入 Redis / 数据库 / 云盘。
- **更换分片存储**：实现 `ChunkStorage`，接入 OSS / HDFS / S3。
- **覆盖默认组件**：Spring Boot 下所有核心 Bean 均为
  `@ConditionalOnMissingBean`，定义同名 Bean 即可覆盖。

## 并发与一致性

- 同一任务的分片操作用 `synchronized(task)` 串行化；
- 分片与元数据写盘采用「临时文件 + 原子改名」，避免中断留下半个文件；
- 重复上传同一分片是幂等的（已记录则直接跳过）。
