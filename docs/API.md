# HTTP API Reference

> 🇺🇸 [English](API.md)

Default Servlet mappings: `/upload`, `/download` (under Spring Boot they can be changed via
`upload-file.upload-url` / `upload-file.download-url`). All responses are UTF-8.

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

Errors (`400`): invalid parameters, MD5 mismatch, task already merged, chunk metadata inconsistent
with the first chunk (`chunkTotal` / `chunkSize` / `fileSize` / `fileName`), chunk exceeding
`max-chunk-size` / `chunk.max-size`.

## 2. Query Upload Progress

```
GET /upload?action=progress&identifier=<identifier>
```

Same response shape as "Upload a Chunk". When the task does not exist, an empty progress is
returned (`uploadedCount=0`) so the client can treat it as a brand-new upload.

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

Errors (`400`): task not found, chunks incomplete, merged size does not match `fileSize`.

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

> Compatibility: content below 2 GB can be downloaded on a Servlet 3.0 container; range
> responses above 2 GB use `setContentLengthLong`, which requires a Servlet 3.1+ container.

## Suggested Client Flow (Resumable Upload)

1. Compute the whole-file MD5 and use it as `identifier`;
2. Before resuming, call `GET /upload?action=progress` and skip the chunks already present
   in `uploadedChunks`;
3. Upload the missing chunks one by one; if any chunk fails, only that chunk is re-uploaded;
4. Call merge once all chunks are uploaded;
5. For downloads, carry the `Range` header to resume from the last breakpoint.
