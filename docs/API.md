# HTTP API Reference

> 🇺🇸 [English](API.md)

Default Servlet mappings: `/upload`, `/download` (under Spring Boot they can be changed via
`upload-file.upload-url` / `upload-file.download-url`). All responses are UTF-8.

> **Which artifact serves this API?** The protocol below is identical across both servlet generations.
> Spring Boot 2.x / Servlet 3/4 (`javax.servlet`) deployments use `upload-file-servlet` /
> `upload-file-spring-boot-starter`; Spring Boot 3/4 / Tomcat 10+ (`jakarta.servlet`) deployments use the
> drop-in twins `upload-file-servlet-jakarta` / `upload-file-spring-boot-starter-jakarta` (same FQCNs, same
> `upload-file.*` properties — swap the Maven coordinate, change no code). A `javax` artifact and its
> `-jakarta` twin must never share a classpath. Manual `upload-file-core` wiring (the rc.4 path-finder recipe)
> exposes the same protocol on any stack. See the [README](../README.md) for coordinates and quick start.

## 0. Access Control (rc.3)

When access control is enabled (`security.enabled=true` with a configured `security.token`), every
endpoint requires the token in the header named by `security.header-name` (default `X-Access-Token`)
or in a `token` query parameter. Missing or wrong tokens return `401`. When disabled (default), no
token is needed.

Common error statuses (in addition to the endpoint-specific ones below). Since rc.4 these map to the
`UploadErrorCode` carried by the core exceptions, so HTTP-layer integrations report identical statuses:

| Status | Meaning |
| --- | --- |
| `400` | invalid parameters, metadata disagreement, MD5 mismatch, or exceeding `max-file-size` / `chunk.max-size` |
| `401` | access denied (access control enabled, missing/wrong token) |
| `404` | task not found |
| `409` | merge-state conflict (uploading to an already-merged/in-flight task, merge with missing chunks, cancelling during an async merge) |
| `416` | unsatisfiable `Range` |
| `507` | exceeds the global capacity quota `quota.max-bytes` (`Insufficient Storage`) |

## 1. Upload a Chunk

```
POST /upload
Content-Type: multipart/form-data
```

multipart fields:

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `identifier` | string | yes | unique file ID (whole-file MD5 is recommended) |
| `fileName` | string | yes | original file name |
| `fileSize` | long | no | total file size in bytes (used for merge validation) |
| `chunkSize` | long | no | chunk size; `<=0` uses the server default (5 MB) |
| `chunkTotal` | int | yes | total number of chunks |
| `chunkIndex` | int | yes | current chunk index (starts at 0) |
| `chunkMd5` | string | no | MD5 of this chunk (used when verification is enabled) |
| `file` | file | yes | chunk content (field name is fixed as `file`) |

Response `200`:

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

Notes: re-uploading the same chunk is skipped (idempotent); the current progress is returned.

Errors (`400`): invalid parameters, MD5 mismatch, chunk metadata inconsistent with the first chunk
(`chunkTotal` / `chunkSize` / `fileSize` / `fileName`), chunk exceeding `max-chunk-size` /
`chunk.max-size`, or the whole file exceeding `max-file-size`.
`409` when the task is already merged or an async merge is pending/running/finished.
`507` when accepting the file would exceed `quota.max-bytes`; `401` when access control is enabled
and the token is missing/wrong.

## 2. Query Upload Progress

```
GET /upload?action=progress&identifier=<identifier>
```

Same response shape as "Upload a Chunk". When the task does not exist, an empty progress is
returned (`uploadedCount=0`) so the client can treat it as a brand-new upload.

> Note (rc.6): this endpoint requires `action=progress`; a `GET /upload` without an `action`, or
> with an unknown one, returns `400` (`MISSING_ACTION` / `UPLOAD_UNKNOWN_ACTION`).

## 3. Merge Chunks

```
POST /upload?action=merge&identifier=<identifier>
```

Response `200`:

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

Errors: `404` when the task does not exist, `409` when chunks are missing (merge before the upload
completes), `400` when the merged size does not match `fileSize` or the file exceeds `max-file-size`.
`507` when the merge would exceed `quota.max-bytes`; `401` when access control is enabled and the
token is missing/wrong.

## 3.1 Submit an Async Merge

```
POST /upload?action=mergeAsync&identifier=<identifier>
```

Submits the merge to a background executor and returns the current status with HTTP `202`.
Submitting the same identifier while `PENDING`/`RUNNING` is idempotent. While a merge is
`PENDING`/`RUNNING`/`SUCCEEDED`, new chunk uploads are rejected.

Response `202`:

```json
{
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "state": "PENDING",
  "merged": false
}
```

State machine: `NONE -> PENDING -> RUNNING -> SUCCEEDED/FAILED`.

## 3.2 Query the Async Merge Status

```
GET /upload?action=mergeStatus&identifier=<identifier>
```

Returns the current async-merge status; a task that was never submitted (or does not exist)
reports `NONE`. A synchronously merged task reports `SUCCEEDED`.

```json
{
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "state": "SUCCEEDED",
  "merged": true,
  "finalPath": "/data/upload/files/55e1c5ec9e2389c5be429808c9800131/demo.bin",
  "finalFileSize": 11534336
}
```

On `FAILED`, the `message` field carries the server-side error reason.

## 3.3 Cancel a Task (rc.4)

```
POST /upload?action=cancel&identifier=<identifier>
```

Removes the task record, its uploaded chunks and any merged artifact dir, so the identifier can be
reused for a brand-new upload. This is the explicit way to reclaim data on abandonment, or right
after the merged artifact has been moved into business storage during the confirm phase.

Response `200`:

```json
{
  "success": true,
  "message": "Upload task cancelled",
  "identifier": "55e1c5ec9e2389c5be429808c9800131"
}
```

Errors: `404` when the task does not exist, `409` while the async merge is `PENDING`/`RUNNING`
(retry once it settles), `401` when access control is enabled and the token is missing/wrong.
Since rc.6, `upload-file.http.cancel-not-found-status=200` maps the not-found case to an
idempotent `200` (body stays truthful: `UploadResult.success=false`, message "Upload task not found").

## 4. Download (Resumable)

```
GET /download?identifier=<identifier>
```

Optional request header:

| Header | Description |
| --- | --- |
| `Range: bytes=0-499` | specific range |
| `Range: bytes=500-` | from byte 500 to the end of the file |
| `Range: bytes=-500` | the last 500 bytes |

Responses:

| Scenario | Status | Description |
| --- | --- | --- |
| No `Range` | `200` | full file, `Content-Length` equals file size |
| Satisfiable `Range` | `206` | carries `Content-Range: bytes start-end/total` |
| Unsatisfiable `Range` | `416` | carries `Content-Range: bytes */total` |
| File not found | `404` | — |

`Accept-Ranges: bytes` and `Content-Disposition: attachment` are always sent.

> Note (rc.6): download failures (missing `identifier` → `400`, file not found → `404`, unsatisfiable
> `Range` → `416`, access denied → `401`/`403`) now return a JSON failure body with a symbolic code
> (`MISSING_IDENTIFIER` / `UPLOAD_NOT_FOUND` / `RANGE_NOT_SATISFIABLE` / `ACCESS_DENIED`), shaped by
> `http.error-body` exactly like the upload endpoint; `416` still carries `Content-Range: bytes */<size>`.

> Compatibility: content below 2 GB can be downloaded on a Servlet 3.0 container; range
> responses above 2 GB use `setContentLengthLong`, which requires a Servlet 3.1+ container.

> Note (rc.6): under the Spring Boot starter the download servlet is registered only when
> `upload-file.endpoint.download-enabled=true` (off by default).

## 5. Error Codes & Failure Bodies (rc.6)

Every typed failure carries a stable symbolic code via `UploadErrorCode.code()` (catalog
`UploadErrorCodes`), independent of the JSON body shape. The body shape is selected by
`upload-file.http.error-body` (servlet init-param `http.error-body`):

- `legacy` (default) – the rc.5 per-endpoint models (`UploadProgress.empty` /
  `UploadResult.error` / `MergeStatus.none`; download errors also render as `UploadResult.error`);
- `standard` – a uniform `UploadHttpError{code,status,message,identifier,action}`.

> The upload and download endpoints share the same renderer and symbolic-code catalog. The status for
> canceling a missing task is controlled by `upload-file.http.cancel-not-found-status` (servlet init-param
> `cancel-not-found-status`).

| Code | Typical HTTP | Meaning |
| --- | --- | --- |
| `UPLOAD_VALIDATION` | 400 | invalid parameters, metadata disagreement, size limits, missing chunks on merge |
| `UPLOAD_CHECKSUM` | 400 | chunk MD5 mismatch (only that chunk is rejected) |
| `UPLOAD_NOT_FOUND` | 404 | task does not exist |
| `UPLOAD_MERGE_CONFLICT` | 409 | merge-state conflict (chunk to a merged/in-flight task, cancel during async merge) |
| `ACCESS_DENIED` | 401/403 | access-control rejection (status from the `AccessDecision`) |
| `QUOTA_EXCEEDED` | 507 | exceeds the global capacity quota |
| `MISSING_ACTION` | 400 | `GET /upload` without an `action` parameter (rc.6) |
| `UPLOAD_UNKNOWN_ACTION` | 400 | `GET /upload` with an unknown `action` (rc.6) |
| `MISSING_IDENTIFIER` | 400 | required `identifier` parameter missing |
| `UPLOAD_SERVER_ERROR` | 500 | server-side failure (rc.6: no longer collapsed to 400) |
| `RANGE_NOT_SATISFIABLE` | 416 | unsatisfiable `Range` |

## Suggested Client Flow (Resumable Upload)

1. Compute the whole-file MD5 and use it as `identifier`;
2. Before resuming, call `GET /upload?action=progress` and skip the chunks already present
   in `uploadedChunks`;
3. Upload the missing chunks one by one; if any chunk fails, only that chunk is re-uploaded;
4. Call merge once all chunks are uploaded;
5. For downloads, carry the `Range` header to resume from the last breakpoint.
