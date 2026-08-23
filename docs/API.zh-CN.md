# HTTP API 参考

> 🇺🇸 [English](API.md)

默认 Servlet 映射：`/upload`、`/download`（Spring Boot 下可通过 `upload-file.upload-url` / `upload-file.download-url` 修改）。
所有响应均为 UTF-8。

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

错误（`400`）：参数非法、MD5 不一致、任务已合并。

## 2. 查询上传进度

```
GET /upload?action=progress&identifier=<identifier>
```

响应同「上传分片」。任务不存在时返回空进度（`uploadedCount=0`），客户端可视为全新上传。

## 3. 合并分片

```
POST /upload?action=merge&identifier=<identifier>
```

响应 `200`：

```json
{
  "success": true,
  "message": "合并成功",
  "identifier": "55e1c5ec9e2389c5be429808c9800131",
  "chunkTotal": 3,
  "uploadedCount": 3,
  "merged": true,
  "finalPath": "/data/upload/files/55e1c5ec9e2389c5be429808c9800131/demo.bin",
  "finalFileSize": 11534336
}
```

错误（`400`）：任务不存在、分片不完整、合并后文件大小与 `fileSize` 不一致。

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

> 兼容性：小于 2GB 的内容在 Servlet 3.0 容器即可下载；大于 2GB 的区间响应使用
> `setContentLengthLong`，需要 Servlet 3.1+ 容器。

## 客户端建议流程（断点续传）

1. 计算整个文件 MD5 作为 `identifier`；
2. 每次续传前先 `GET /upload?action=progress`，跳过 `uploadedChunks` 中已存在的分片；
3. 依次上传缺失分片，任一分片失败仅需重传该分片；
4. 全部分片上传完成后调用 merge；
5. 下载时携带 `Range` 头即可从上次断点继续。
