# WebSocket 接口文档 - ask_user 交互

## 二、WebSocket 出参（后端→前端）

### 消息类型：approval-request

**场景1：标准问卷**
```json
{
  "type": "approval-request",
  "approval_id": "uuid-string",
  "operation_type": "questionnaire",
  "mode": "questionnaire",
  "message": "Please answer the following questions",
  "risk_level": "LOW",
  "agent_name": "agent-name",
  "parent_instance_id": "session_xxx",
  "timestamp": "2026-07-31T10:00:00",
  "questions": [
    {
      "question": "请选择技术方案",
      "options": [
        {"label": "方案A（推荐）", "description": "使用REST API"},
        {"label": "方案B", "description": "使用GraphQL"},
        {"label": "方案C", "description": "使用gRPC"}
      ],
      "multi_select": false
    },
    {
      "question": "请选择部署环境",
      "options": [
        {"label": "开发环境"},
        {"label": "测试环境"},
        {"label": "生产环境"}
      ],
      "multi_select": true
    }
  ]
}
```


### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 "approval-request" |
| approval_id | string | 是 | 请求唯一标识，用于响应时匹配 |
| operation_type | string | 是 | 固定值 "questionnaire" |
| mode | string | 是 | 固定值 "questionnaire" |
| message | string | 是 | 描述文本 |
| risk_level | string | 是 | 风险等级，固定值 "LOW" |
| agent_name | string | 否 | 触发问卷的Agent名称 |
| parent_instance_id | string | 是 | 父实例ID，用于前端路由 |
| timestamp | string | 是 | 时间戳 ISO格式 |
| questions | array | 是 | 问题列表（1-4个） |
| metadata | object | 是 | 元数据，包含confirmation_type、questions、file_path、file_content |

**questions[i] 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | string | 是 | 问题文本 |
| options | array | 是 | 选项列表（2-6个） |
| multi_select | boolean | 否 | 是否多选，默认false |

**options[i] 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | string | 是 | 选项标签 |
| description | string | 否 | 选项说明 |

## 三、WebSocket 入参（前端→后端）

### 消息类型：approval-response

**场景1：单选/多选问卷响应**
```json
{
  "type": "approval-response",
  "request_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": "方案A（推荐）",
      "请选择部署环境": ["开发环境", "测试环境"]
    },
    "ignore": false
  }
}
```

**场景3：忽略问卷**
```json
{
  "type": "approval-response",
  "request_id": "uuid-string",
  "approved": false,
  "scope": "once",
  "questionnaire_answers": {
    "ignore": true
  }
}
```

**场景4：选择Other自定义文本**
```json
{
  "type": "approval-response",
  "request_id": "uuid-string",
  "approved": true,
  "scope": "once",
  "questionnaire_answers": {
    "label": {
      "请选择技术方案": "用户自定义答案"
    },
    "ignore": false
  }
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定值 "approval-response" |
| request_id | string | 是 | 使用approval-request的approval_id进行响应匹配 |
| approved | boolean | 是 | 是否批准/响应 |
| scope | string | 是 | 作用域，固定值 "once" |
| questionnaire_answers | object | 是 | 问卷答案对象 |

**questionnaire_answers 参数说明**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | object | 否 | 答案对象，key为问题文本，value为答案（单选为字符串，多选为数组） |
| ignore | boolean | 否 | 是否忽略问卷，为true时工具返回失败 |


## 五、超时处理

后端设置60秒超时：
- Agent调用ask_user时设置timeout_seconds=60
- 60秒内前端未响应 → 后端返回 `{"error": "Questionnaire timed out after 60 seconds", "failed": true}`
- Agent可根据failed字段判断失败原因并重试或使用默认方案

## 测试示例


```
生成一个调查问卷{
      "问题文本1": "单选答案",
      "问题文本2": [
        "多选答案1",
        "多选答案2"
      ]
    }
```
