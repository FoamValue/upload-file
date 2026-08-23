# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> 🇨🇳 [简体中文](CHANGELOG.zh-CN.md)

## [Unreleased]

- Planned: support for custom `TaskStore` / `ChunkStorage` implementations documented with examples (Redis / OSS / S3).
- Planned: Spring Boot 3.x (`jakarta.servlet`) adapter.

## [1.0.0-rc.1] - 2026-08-23

**Pre-release.** First release candidate of the initial version. API may still change before `1.0.0`.

### Added

- Chunked upload of large files; only failed chunks are re-transferred
- Resumable (breakpoint) upload: the server records uploaded chunks, clients can pause and resume
- Optional per-chunk MD5 verification
- Chunk merge: ordered merge, final-size validation, automatic chunk cleanup
- Resumable download based on HTTP `Range` (`206 Partial Content`, `416` for unsatisfiable ranges)
- Task metadata persistence: in-memory (`MemoryTaskStore`) or local JSON file (`FileTaskStore`, survives restarts)
- Pluggable storage SPIs: `TaskStore` (metadata) and `ChunkStorage` (chunks)
- Plain Servlet 3.0+ integration: `UploadServlet` / `DownloadServlet`
- Spring Boot 2.x auto-configuration: `upload-file-spring-boot-starter` (zero-config, `@ConditionalOnMissingBean` overridable)
- Demo application with a frontend page: `example/upload-file-demo`
- Bilingual documentation (English / 简体中文)

### Security

- `identifier` and `fileName` are validated to prevent path traversal (including merge-time re-validation)
- Chunks and metadata are written atomically (temp file + rename), avoiding partial files
- Defense-in-depth identifier validation inside `FileTaskStore` / `LocalFileChunkStorage`

### Fixed

- Path traversal via `fileName` when merging chunks
- Concurrent upload race on task creation (striped lock per identifier)
- Checksum mismatch no longer discards the entire upload progress (only the offending chunk is rejected)
- `Content-Length` handling for files below 2 GB on Servlet 3.0 containers (`setContentLength` fallback)

### Changed

- Build targets `--release 8`; producing JDK 8 bytecode requires JDK 9+ to build from source
- Metadata/chunk writes moved from direct writes to atomic temp-file + rename

### Dependencies

- Gson 2.10.1, javax.servlet-api 4.0.1 (provided), Spring Boot 2.7.18 (provided, starter only)
