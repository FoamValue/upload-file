# Future Optimization Directions

> 🇨🇳 [简体中文](ROADMAP.zh-CN.md)

## Current Optimization Plan

- The P0 items (5) are **implemented in `V1.0.0-rc.2`** (see the [V1.0.0 Task Development Plan](PLAN-V1.0.0-rc.2.md) for the original breakdown).
- **`V1.0.0-rc.3` production-readiness hardening** (access control / multi-instance constraints / size-quota / observability / migration / compat regression / 1.0.0 SOW) is planned — see [V1.0.0-rc.3 Task Plan](PLAN-V1.0.0-rc.3.md).
- **`V1.0.0-rc.4`** (feedback-driven) is done: stable per-identifier read (`getTask`) + explicit cancellation (`cancelUpload` / `action=cancel`), stable `UploadErrorCode` HTTP semantics, and cleanup / manual-wiring documentation — see the [V1.0.0-rc.4 Task Plan](PLAN-V1.0.0-rc.4.md), the [Changelog](../CHANGELOG.md) and the driving feedback in `doc/user-feedback/`.
- **`V1.0.0-rc.5`** (jakarta adapter, feedback P0-1) is done: official `-jakarta` servlet module and Spring Boot **4.0.0+** starter (drop-in twins) plus a Boot 4 demo — see [V1.0.0-rc.5 Task Plan](PLAN-V1.0.0-rc.5.md) and the [Changelog](../CHANGELOG.md).
- **`V1.0.0-rc.6`** (commercial HTTP-layer adoption: security & audit alignment) is done: controllable endpoint registration (`/download` off by default, beans-only mode), additive decision-returning `AccessControl` (`401`/`403` distinguishable) with audit hooks and `access-log`, symbolic error codes + opt-in `standard` error body, configurable multipart strategy, servlet behaviour convergence, and a "self-built MVC endpoint → official Servlet" migration guide — see [V1.0.0-rc.6 Task Plan](PLAN-V1.0.0-rc.6.md) and the [Changelog](../CHANGELOG.md).
- P1/P2 items are pending confirmation.

## Complete Optimization List

The following are optional directions for future releases. P0 items are implemented in `1.0.0-rc.2`;
P1/P2 items are not yet implemented. Sorted by priority, highest first.

| Priority | Direction | Problem it solves | Notes | Proposed date | Planned release version |
| --- | --- | --- | --- | --- | --- |
| P0 ✅ | Expired-task cleanup (TTL/GC) | incomplete tasks and leftover chunks have no expiry mechanism and accumulate indefinitely on a long-running deployment | scheduled cleanup or configurable TTL built on `UploadTask.updateTime` | 2026-08-25 | implemented in V1.0.0-rc.2 |
| P0 ✅ | Atomic merge | a crash mid-merge leaves a corrupt file | merge to a temp file in the same dir, rename on success, then update metadata | 2026-08-25 | implemented in V1.0.0-rc.2 |
| P0 ✅ | Orphan data GC | leftover chunks/files cannot be reclaimed after metadata loss | startup scan + periodic diff between TaskStore and disk, clean orphans | 2026-08-25 | implemented in V1.0.0-rc.2 |
| P0 ✅ | Async merge | large-file merge blocks the HTTP request and may time out | task queue + callback/polling for progress | 2026-08-25 | implemented in V1.0.0-rc.2 |
| P0 ✅ | Pluggable metadata storage | multi-node / high availability | Redis/DB-backed TaskStore (SPI already provides the extension point) | 2026-08-25 | implemented in V1.0.0-rc.2 |
| P1 | Multi-tenancy | multiple people/businesses share one deployment | prefix identifier with a namespace; invisible across tenants, per-tenant quotas | 2026-08-25 | None (updated dynamically) |
| P1 | Quota & rate limiting | per-user capacity cap, global throttling | upload/download rate limit, total capacity and per-file size quotas | 2026-08-25 | part: global capacity quota (`quota.max-bytes`) and per-file size limit shipped in V1.0.0-rc.3; per-user quota & rate limiting remain |
| P1 | Instant upload (policy B) | re-uploading large files wastes bandwidth and time | served instantly only when the `identifier` exists in the `TaskStore` with `merged=true` and the final file is on disk; check runs on `getProgress` or the first `uploadChunk`; prerequisite: configure `upload-file.metadata-dir` (`FileTaskStore`), otherwise records are lost on restart; boundary: files manually copied into `files/<id>/` are not instant-uploaded | 2026-08-25 | None (updated dynamically) |
| P1 | Content integrity check | server never verifies the whole-file hash, so content is not guaranteed to match the identifier | upgrade the `identifier` convention from whole-file MD5 to whole-file SHA-256; after merge the server recomputes the merged file's SHA-256 and compares it to the identifier, deleting the file and failing on mismatch; linked changes: client hashing algorithm, `ChecksumUtil`, `chunkMd5` semantics, docs/demos/tests | 2026-08-25 | None (updated dynamically) |
| P1 | Single copy + alias index (storage model) | identical content under different file names wastes disk and aliases cannot be downloaded | physical file stored at `files/<identifier>/<identifier>`, identical content stored once; alias index `identifier -> List<fileName>` (including upload time etc.); instant upload = add the new name to the alias list; downloads resolve the physical file by identifier and use the requested alias for `Content-Disposition` (download API needs alias-based lookup or an alias list) | 2026-08-25 | None (updated dynamically) |
| P1 | Cross-task chunk content dedup | multiple similar files with many identical chunks each keep a copy, wasting disk | key chunk storage by chunk MD5 plus reference counting, storing identical content once and assembling the merged file by references; note: fixed-offset chunking means identical chunks appear frequently only when differences align with chunk boundaries; stronger dedup needs Content-Defined Chunking (CDC), which changes chunk boundaries and resumable-upload semantics | 2026-08-25 | None (updated dynamically) |
| P1 | Support the tus protocol | high client integration cost, fragmented ecosystem | tus is the de-facto resumable-upload standard; existing front-end SDKs can be reused once compatible | 2026-08-25 | None (updated dynamically) |
| P1 | Multi-language SDK | HTTP-only API raises integration cost for business teams | Java/JS/Python, wrapping chunking, retry, and resume | 2026-08-25 | None (updated dynamically) |
| P1 | Object-storage backend | single-node disk fills up, no scalability | wire S3/MinIO/OSS via the existing `ChunkStorage` SPI; or client-side direct upload coordinated by server signing | 2026-08-25 | None (updated dynamically) |
| P1 | Recycle bin / soft delete | accidental deletion is irreversible | mark deleted first, physically remove after a TTL | 2026-08-25 | None (updated dynamically) |
| P1 | Audit logging | compliance and traceability | full audit trail for upload/download/instant upload | 2026-08-25 | None (updated dynamically) |
| P2 | Virus scanning | uploaded files may contain malicious content | async scan after upload (ClamAV); download blocked until clean | 2026-08-25 | None (updated dynamically) |
| P2 | File-type whitelist + magic-byte check | executables or disguised file types can be uploaded | extension whitelist + file magic-byte validation | 2026-08-25 | None (updated dynamically) |
| P2 | Presigned download links | shares lack expiry and access control | shares with expiry, password, and usage limits | 2026-08-25 | None (updated dynamically) |
| P2 | Versioning | re-uploading the same identifier overwrites history | keep historical versions on re-upload | 2026-08-25 | None (updated dynamically) |
| P2 | Encryption at rest | data stored in plaintext | disk-level or field-level encryption | 2026-08-25 | None (updated dynamically) |
| P2 | Compression | large files consume bandwidth and disk | transparent compression by content type to save bandwidth and disk | 2026-08-25 | None (updated dynamically) |
| P2 | Preview / transcoding | no online preview of images/videos | image thumbnails, video transcoding, decoupled from the upload pipeline | 2026-08-25 | None (updated dynamically) |
| P2 | Webhook | business teams cannot observe upload-complete events | push events on upload complete / instant-upload hit | 2026-08-25 | None (updated dynamically) |
| P2 | Observability | lack of metrics and operational views | Prometheus metrics, structured logs, admin dashboard (storage stats, task queries) | 2026-08-25 | None (updated dynamically) |
