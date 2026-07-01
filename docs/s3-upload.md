# S3 文件上传接口

## ChatService 接入方式

ChatService 不直接向前端暴露下游 `/uploadFile`。前端仍统一调用：

```http
POST /api/v1/ex/documents
```

当需要使用本文档描述的 S3 upload API 时，multipart 请求中增加：

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `targetProvider` | `String` | 是 | 固定传 `s3-upload`。 |

示例：

```bash
curl -X POST 'http://localhost:8080/api/v1/ex/documents' \
  -F 'file=@/path/to/example.pdf' \
  -F 'targetProvider=s3-upload'
```

后端会根据 `targetProvider=s3-upload` 转发到下游 `/uploadFile`，并把下游返回的
`docId/docName/url/docSize/docRelativePath/docStatus/fileSize/message/error` 写入统一文档库的
`UploadedDocumentDto.metadataJson.providerDocument`。

启用配置：

```bash
export FINANCEEX_S3_UPLOAD_DOCUMENT_PROVIDER_ENABLED=true
export FINANCEEX_S3_UPLOAD_BASE_URL=https://s3-upload.example.com
export FINANCEEX_S3_UPLOAD_PATH=/uploadFile
```

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口路径 | `/uploadFile` |
| 请求方法 | `POST` |
| Content-Type | `multipart/form-data` |
| Controller | `FileOperateController` |
| Service | `IFileOperateService.uploadFile()` |
| 实现类 | `FileOperateService.uploadFile()` |

## 请求参数

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | `MultipartFile` | 是 | 待上传的文件。 |

## 响应参数

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `String` | 响应状态，取值为 `success` 或 `fail`。 |
| `message` | `String` | 响应消息。 |
| `data` | `List<S3ImageVo>` | 文件上传结果列表。 |

## `S3ImageVo` 结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `docId` | `String` | 文档 ID。 |
| `docName` | `String` | 文档名称。 |
| `url` | `String` | 文件访问 URL。 |
| `docSize` | `String` | 文档大小。 |
| `docRelativePath` | `String` | 相对路径。 |
| `docStatus` | `Integer` | 文档状态。 |
| `fileSize` | `Integer` | 文件大小。 |
| `message` | `String` | 消息。 |
| `error` | `Integer` | 错误码。 |

## 业务逻辑

1. 校验文件和文件名是否为空。
2. 校验通过后，将文件上传到 S3 存储。
3. 返回文件上传结果。

## 错误码

| 响应状态 | 响应消息 | 说明 |
| --- | --- | --- |
| `fail` | `文件或文件名不能为空` | 文件为空或文件名为空。 |

## 请求示例

```bash
curl -X POST 'http://localhost:8080/uploadFile' \
  -H 'Content-Type: multipart/form-data' \
  -F 'file=@/path/to/example.pdf'
```

## 成功响应示例

```json
{
  "status": "success",
  "message": "上传成功",
  "data": [
    {
      "docId": "doc_xxx",
      "docName": "example.pdf",
      "url": "https://example.com/example.pdf",
      "docSize": "102400",
      "docRelativePath": "/2026/07/example.pdf",
      "docStatus": 1,
      "fileSize": 102400,
      "message": "success",
      "error": 0
    }
  ]
}
```

## 失败响应示例

```json
{
  "status": "fail",
  "message": "文件或文件名不能为空",
  "data": []
}
```
