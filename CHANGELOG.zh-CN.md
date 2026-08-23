# 更新日志

本文件记录项目的所有重要变更。

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 🇺🇸 [English](CHANGELOG.md)

## [Unreleased]

- 计划：补充自定义 `TaskStore` / `ChunkStorage` 实现（Redis / OSS / S3）的文档与示例。
- 计划：Spring Boot 3.x（`jakarta.servlet`）适配。

## [1.0.0-rc.1] - 2026-08-23

**Pre-release。** 初版的首个候选版本，`1.0.0` 正式版发布前 API 仍可能调整。

### 新增

- 大文件分片上传，失败仅重传失败分片
- 断点续传：服务端记录已上传分片，客户端可随时暂停/继续
- 可选的分片 MD5 校验
- 分片合并：按序合并、最终大小校验、自动清理分片
- 基于 HTTP `Range` 的断点续传下载（`206 Partial Content`，不可满足时返回 `416`）
- 任务元数据持久化：内存（`MemoryTaskStore`）或本地 JSON 文件（`FileTaskStore`，重启不丢）
- 可插拔存储 SPI：`TaskStore`（元数据）与 `ChunkStorage`（分片）
- 纯 Servlet 3.0+ 接入：`UploadServlet` / `DownloadServlet`
- Spring Boot 2.x 自动配置：`upload-file-spring-boot-starter`（零配置，`@ConditionalOnMissingBean` 可覆盖）
- 演示用例（含前端页面）：`example/upload-file-demo`
- 中英文双语文档

### 安全

- `identifier` 与 `fileName` 均做路径穿越校验（合并写盘前再次校验）
- 分片与元数据采用「临时文件 + 原子改名」写盘，避免残留半个文件
- `FileTaskStore` / `LocalFileChunkStorage` 内部对 identifier 做防御性重复校验

### 修复

- 合并分片时通过 `fileName` 实现的路径穿越
- 并发首片上传的任务创建竞态（改为按 identifier 的 striped lock）
- MD5 校验不一致时不再丢弃整个上传进度（仅拒收该分片）
- Servlet 3.0 容器下小于 2GB 文件的 `Content-Length` 兼容（`setContentLength` 回退）

### 变更

- 编译目标 `--release 8`，产物为 JDK8 字节码；从源码构建需要 JDK 9+
- 分片与元数据改为原子写盘（临时文件 + 改名）

### 依赖

- Gson 2.10.1、javax.servlet-api 4.0.1（provided）、Spring Boot 2.7.18（provided，仅 starter）
