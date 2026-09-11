# HTTP API 参考

> 🇺🇸 [English](API.md)

默认 Servlet 映射：`/upload`、`/download`（Spring Boot 下可通过 `upload-file.upload-url` / `upload-file.download-url` 修改）。
所有响应均为 UTF-8。

> **用哪个产物提供本 API？** 下面的协议在两个 servlet 世代中完全一致。Spring Boot 2.x / Servlet 3/4
> （`javax.servlet`）部署使用 `upload-file-servlet` / `upload-file-spring-boot-starter`；Spring Boot 3/4 /
> Tomcat 10+（`jakarta.servlet`）部署使用其无缝孪生版 `upload-file-servlet-jakarta` /
> `upload-file-spring-boot-starter-jakarta`（FQCN 与 `upload-file.*` 属性一致——只换 Maven 坐标，不改代码）。
> `javax` 产物与其 `-jakarta` 孪生版不可同存于同一 classpath。手工装配 `upload-file-core`（rc.4 的
> path-finder 配方）在任何技术栈上都暴露相同协议。坐标与快速开始见 [README](../README.zh-CN.md)。

## 0. 访问控制（rc.3）

启用访问控制后（`security.enabled=true` 且配置了 `security.token`），所有接口都要求携带令牌：
通过 `security.header-name` 指定的请求头（默认 `X-Access-Token`）或 `token` 查询参数传递。
令牌缺失或错误时返回 `401`。未启用（默认）时无需令牌。

 通用错误状态码（除各接口自身的错误外）。自 rc.4 起与 core 异常携带的 `UploadErrorCode` 一一对应，
 HTTP 层集成上报的状态码一致：

| 状态码 | 含义 |
| --- | --- |
| `400` | 参数非法、元数据不一致、MD5 不匹配，或超过 `max-file-size` / `chunk.max-size` |
| `401` | 访问被拒（启用访问控制，令牌缺失/错误） |
| `404` | 任务不存在 |
| `409` | 合并状态冲突（向已合并/合并中的任务传分片、缺分片即合并、异步合并期间取消） |
| `416` | `Range` 不可满足 |
| `507` | 超过全局容量配额 `quota.max-bytes`（`Insufficient Storage`） |

## 1. 上传分片

```
POST /upload
Content-Type: multipart/form-data
```

multipart 字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `identifier` | string | 是 | 文件唯一标识（建议整文件 MD5） |
| `fileName` | string | 是 | 原始文件名 |
| `fileSize` | long | 否 | 整个文件字节数（合并时用于校验） |
| `chunkSize` | long | 否 | 分片大小；≤0 时用服务端默认（5MB） |
| `chunkTotal` | int | 是 | 总分片数 |
| `chunkIndex` | int | 是 | 当前分片序号（从 0 开始） |
| `chunkMd5` | string | 否 | 当前分片 MD5（开启校验时生效） |
| `file` | file | 是 | 分片内容（字段名固定 `file`） |

响应 `200`：

```json
{
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "fileName": "demo.bin",
  "fileSize": 11534336,
  "chunkSize": 5242880,
  "chunkTotal": 3,
  "uploadedChunks": [0, 1],
  "uploadedCount": 2,
  "progressPercent": 66,
  "merged": false
}
```

说明：同一分片重复上传直接跳过（幂等），返回当前进度。

错误（`400`）：参数非法、MD5 不一致、分片元数据与首片不一致
（`chunkTotal` / `chunkSize` / `fileSize` / `fileName`）、分片超过
`max-chunk-size` / `chunk.max-size`，或整个文件超过 `max-file-size`。
任务已合并或异步合并处于 PENDING/RUNNING/SUCCEEDED 时返回 `409`。
接受该文件将超过 `quota.max-bytes` 时返回 `507`；启用访问控制且令牌缺失/错误时返回 `401`。

## 2. 查询上传进度

```
GET /upload?action=progress&identifier=<identifier>
```

响应同「上传分片」。任务不存在时返回空进度（`uploadedCount=0`），客户端可视为全新上传。

> 说明（rc.6）：本端点要求 `action=progress`；`GET /upload` 缺失 `action` 或 action 未知时返回 `400`
> （`MISSING_ACTION` / `UPLOAD_UNKNOWN_ACTION`）。

## 3. 合并分片

```
POST /upload?action=merge&identifier=<identifier>
```

响应 `200`：

```json
{
  "success": true,
  "message": "Merged successfully",
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "chunkTotal": 3,
  "uploadedCount": 3,
  "merged": true,
  "finalPath": "/data/upload/files/55e1c5ec9e2389c5be429808c9800131/demo.bin",
  "finalFileSize": 11534336
}
```

错误：任务不存在时返回 `404`，分片不完整（未上传完成即合并）时返回 `409`，合并后文件大小与
`fileSize` 不一致或文件超过 `max-file-size` 时返回 `400`。合并将超过 `quota.max-bytes` 时返回 `507`；
启用访问控制且令牌缺失/错误时返回 `401`。

## 3.1 提交异步合并

```
POST /upload?action=mergeAsync&identifier=<identifier>
```

将合并提交到后台线程池执行，返回 HTTP `202` 与当前状态。同一 identifier 在 `PENDING`/`RUNNING`
期间重复提交是幂等的。合并处于 `PENDING`/`RUNNING`/`SUCCEEDED` 时，新的分片上传会被拒绝。

响应 `202`：

```json
{
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "state": "PENDING",
  "merged": false
}
```

状态机：`NONE → PENDING → RUNNING → SUCCEEDED/FAILED`。

## 3.2 查询异步合并状态

```
GET /upload?action=mergeStatus&identifier=<identifier>
```

返回当前异步合并状态；从未提交（或不存在）的任务返回 `NONE`，同步合并完成的任务返回 `SUCCEEDED`。

```json
{
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "state": "SUCCEEDED",
  "merged": true,
  "finalPath": "/data/upload/files/55e1c5ec9e2389c5be429808c9800131/demo.bin",
  "finalFileSize": 11534336
}
```

`FAILED` 时 `message` 字段携带服务端错误原因。

## 3.3 取消任务（rc.4）

```
POST /upload?action=cancel&identifier=<identifier>
```

删除任务记录、已上传分片与合并产物目录，使该 identifier 可重新用于全新上传。这是上传放弃时，
或在 confirm 阶段把合并产物迁入业务存储后的**显式回收**手段。

响应 `200`：

```json
{
  "success": true,
  "message": "Upload task cancelled",
  "identifier": "55e1c5ec9e2389c5be429808c9800131"
}
```

错误：任务不存在返回 `404`；异步合并处于 `PENDING`/`RUNNING` 时返回 `409`（等待其结束后重试）；
启用访问控制且令牌缺失/错误时返回 `401`。rc.6 起 `upload-file.http.cancel-not-found-status=200` 可将
「任务不存在」映射为幂等 `200`（响应体仍如实：`UploadResult.success=false`、文案 "Upload task not found"）。

## 4. 下载（支持断点续传）

```
GET /download?identifier=<identifier>
```

可选请求头：

| 头 | 说明 |
| --- | --- |
| `Range: bytes=0-499` | 指定范围 |
| `Range: bytes=500-` | 从 500 到文件末尾 |
| `Range: bytes=-500` | 最后 500 字节 |

响应：

| 场景 | 状态码 | 说明 |
| --- | --- | --- |
| 无 `Range` | `200` | 完整文件，`Content-Length` 为文件大小 |
| 可满足的 `Range` | `206` | 携带 `Content-Range: bytes start-end/total` |
| 不可满足的 `Range` | `416` | 携带 `Content-Range: bytes */total` |
| 文件不存在 | `404` | — |

始终携带 `Accept-Ranges: bytes` 与 `Content-Disposition: attachment`。

> 说明（rc.6）：下载失败（缺 `identifier` 的 `400`、文件不存在的 `404`、Range 不可满足的 `416`、访问被拒的
> `401`/`403`）与上传端点一致，返回 JSON 失败体并携带符号错误码（`MISSING_IDENTIFIER` /
> `UPLOAD_NOT_FOUND` / `RANGE_NOT_SATISFIABLE` / `ACCESS_DENIED`），形态同样由 `http.error-body` 选择；
> `416` 仍携带 `Content-Range: bytes */<size>`。

> 兼容性：小于 2GB 的内容在 Servlet 3.0 容器即可下载；大于 2GB 的区间响应使用
> `setContentLengthLong`，需要 Servlet 3.1+ 容器。

> 说明（rc.6）：Spring Boot starter 仅在 `upload-file.endpoint.download-enabled=true` 时注册下载 Servlet
> （默认关闭）。

## 5. 错误码与失败响应体（rc.6）

每个类型化失败都经 `UploadErrorCode.code()` 携带稳定符号码（目录 `UploadErrorCodes`），与响应体形态无关。
响应体形态由 `upload-file.http.error-body`（servlet init-param `http.error-body`）选择：

- `legacy`（默认）—— rc.5 端点专用模型（`UploadProgress.empty` / `UploadResult.error` /
  `MergeStatus.none`；下载端点错误亦渲染为 `UploadResult.error`）；
- `standard` —— 统一 `UploadHttpError{code,status,message,identifier,action}`。

> 上传与下载两个端点共用同一渲染器与符号码目录。取消不存在任务的状态由 `upload-file.http.cancel-not-found-status`
> （servlet init-param `cancel-not-found-status`）控制。

| 错误码 | 典型 HTTP | 含义 |
| --- | --- | --- |
| `UPLOAD_VALIDATION` | 400 | 参数非法、元数据不一致、大小超限、合并缺分片 |
| `UPLOAD_CHECKSUM` | 400 | 分片 MD5 不匹配（仅拒该分片） |
| `UPLOAD_NOT_FOUND` | 404 | 任务不存在 |
| `UPLOAD_MERGE_CONFLICT` | 409 | 合并状态冲突（向已合并/合并中任务传分片、异步合并期间取消） |
| `ACCESS_DENIED` | 401/403 | 访问被拒（状态码来自 `AccessDecision`） |
| `QUOTA_EXCEEDED` | 507 | 超过全局容量配额 |
| `MISSING_ACTION` | 400 | `GET /upload` 缺 `action` 参数（rc.6） |
| `UPLOAD_UNKNOWN_ACTION` | 400 | `GET /upload` action 未知（rc.6） |
| `MISSING_IDENTIFIER` | 400 | 缺少必填 `identifier` |
| `UPLOAD_SERVER_ERROR` | 500 | 服务端故障（rc.6 起不再吞成 400） |
| `RANGE_NOT_SATISFIABLE` | 416 | `Range` 不可满足 |

## 客户端建议流程（断点续传）

1. 计算整个文件 MD5 作为 `identifier`；
2. 每次续传前先 `GET /upload?action=progress`，跳过 `uploadedChunks` 中已存在的分片；
3. 依次上传缺失分片，任一分片失败仅需重传该分片；
4. 全部分片上传完成后调用 merge；
5. 下载时携带 `Range` 头即可从上次断点继续。
