# 文件上传接口 1.0

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口地址 | `https://gce-b7.mfg.huawei.com/fina/agent/fileOperate/upload` |
| 请求方法 | `POST` |
| Content-Type | `multipart/form-data` |
| 认证方式 | 由调用方所在系统或网关按企业要求处理。 |

## 请求参数

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | `File` | 是 | 待上传的二进制文件。 |
| `skillId` | `String` | 否 | 技能标识。传入时文件上传到企业文档 EDM 服务；不传时文件上传到 S3。 |

> ChatService 对前端仍只暴露 `POST /api/v1/ex/documents`。前端不要直接传 multipart `skillId`；
> 如需让 ChatService 转发该字段，把技能 ID 放在 `metadata.skillId`。只要 `metadata` 显式包含
> `skillId` 字段，ChatService 的 `api-store` 存储实现就会转成下游 multipart `skillId`；
> `{"skillId":""}` 会原样透传空字符串。

## 上传目标规则

| 场景 | 参数规则 | 上传目标 | 返回特征 |
| --- | --- | --- | --- |
| 企业文档 EDM 上传 | 传入 `skillId` | 企业文档 EDM 服务 | `data[0].docId` 有值，通常返回 `docStatus/docVersion/serverName` 等文档属性。 |
| S3 上传 | 不传 `skillId` | S3 对象存储 | `data[0].url` 有值，`docId/docStatus/docVersion` 等字段通常为空。 |

## 请求示例

### 上传到企业文档 EDM

```bash
curl -X POST 'https://gce-b7.mfg.huawei.com/fina/agent/fileOperate/upload' \
  -F 'skillId=d3334be5e4c241ebb30b40d039919787' \
  -F 'file=@/path/to/AI辅助测试设计穿刺.pptx'
```

### 上传到 S3

```bash
curl -X POST 'https://gce-b7.mfg.huawei.com/fina/agent/fileOperate/upload' \
  -F 'file=@/path/to/big-logo.png'
```

## 响应结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `traceId` | `String` | 请求追踪 ID。 |
| `status` | `String` | 请求状态，成功时为 `success`。 |
| `data` | `Array<FileUploadResult>` | 上传结果列表。 |
| `result` | `Object` | 扩展结果，当前样例为空。 |
| `message` | `String` | 响应消息，当前成功样例为空。 |
| `costTime` | `String/Number` | 调用耗时，当前样例为空。 |

## FileUploadResult 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `docId` | `String` | 企业文档 ID。上传到 EDM 时返回；上传到 S3 时通常为空。 |
| `docName` | `String` | 文件名。 |
| `docSize` | `String` | 文档大小。EDM 场景通常返回字符串形式的字节数。 |
| `docStatus` | `Number` | 文档状态。EDM 场景返回，S3 场景通常为空。 |
| `docVersion` | `String` | 文档版本。EDM 场景返回。 |
| `docRelativePath` | `String` | 文档相对路径。 |
| `url` | `String` | S3 文件访问 URL。不传 `skillId` 上传到 S3 时返回。 |
| `serverName` | `String` | EDM 服务端站点或区域信息。 |
| `wmType` | `String` | 水印类型。 |
| `chunks` | `Array/Object` | 分片信息。 |
| `fileSize` | `Number` | 文件大小扩展字段。 |
| `checkCode` | `String` | 校验码。 |
| `failedDownloadChunks` | `Array/Object` | 下载失败分片信息。 |
| `subAppId` | `String` | 子应用 ID。 |
| `message` | `String` | 单文件上传消息。 |
| `error` | `String/Number` | 单文件上传错误信息或错误码。 |
| `taskId` | `String` | 任务 ID。 |

## 成功响应示例

### 传入 skillId，上传到企业文档 EDM

```json
{
  "traceId": "0535af0ab195249199583d474ba95fb2",
  "data": [
    {
      "docId": "M3T1A4768N1281393779526066372",
      "docSize": "15887275",
      "docStatus": 0,
      "wmType": null,
      "docName": "AI辅助测试设计穿刺.pptx",
      "serverName": "ShenZhen",
      "chunks": null,
      "docRelativePath": "",
      "fileSize": 0,
      "checkCode": null,
      "failedDownloadChunks": null,
      "subAppId": null,
      "docVersion": "V1"
    }
  ],
  "status": "success",
  "result": null,
  "message": null,
  "costTime": null
}
```

### 不传 skillId，上传到 S3

```json
{
  "traceId": "8c195f0a2bd43b6c3236c2729e683d59",
  "data": [
    {
      "docId": null,
      "docName": "big-logo.png",
      "docRelativePath": null,
      "docSize": null,
      "docStatus": null,
      "docVersion": null,
      "failedDownloadChunks": null,
      "fileSize": null,
      "serverName": null,
      "subAppId": null,
      "message": null,
      "error": null,
      "url": "http://s3-beta-hc-kwe.hics.huawei.com/finabot-case-beta/big-logo_20260707232147654.png",
      "taskId": null
    }
  ],
  "status": "success",
  "result": null,
  "message": null,
  "costTime": null
}
```

## 调用注意事项

- `file` 必须使用 multipart 二进制文件字段上传。
- `skillId` 是上传目标的分流参数，不是文件元数据字段。
- 需要上传到企业文档 EDM 时必须传入有效 `skillId`。
- 需要上传到 S3 时不要传 `skillId`。
- 调用方应优先使用 `traceId` 进行问题排查和日志关联。
