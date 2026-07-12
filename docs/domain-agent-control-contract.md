# DomainAgent 拒答、澄清、审批与异常编码握手规范

版本：`v1.0`
适用范围：DomainAgent 与 ChatService/Supervisor 之间的控制事件握手、拒答编码、澄清编码、审批编码、异常编码、敏感信息与数据安全拦截编码。

---

## 1. 规范目标

本文档用于规范 DomainAgent 在无法直接完成当前请求时，向 ChatService/Supervisor 返回稳定、机器可读、可审计的控制事件。

本文档定义：

```text
DomainAgent 返回的事件类型
DomainAgent 返回的编码规则
DomainAgent 返回的字段格式
DomainAgent 如何表达拒答、澄清、审批和异常
Supervisor 如何基于编码消费事件
```

## 2. 核心原则

DomainAgent 只返回当前领域 Agent 的处理事实。

```text
DomainAgent 只判断：当前 Agent 是否能继续处理。
Supervisor 判断：系统后续如何处理。
```

Supervisor 基于 DomainAgent 返回的 `type`、`code`、`reasonCode`、`recoverable`、`inputRequest`、`approvalRequest` 和当前上下文判断是否重新路由、继续澄清、发起审批、重试、降级或最终拒答。

---

# 第一部分：编码规范

## 3. 统一编码格式

编码格式统一为：

```text
FN-{应用简称}-{功能模块}-{错误码类型}-{来源对象}-{码号}
```

当前 ChatService / DomainAgent 场景固定为：

```text
FN-EX-CAHT-{错误码类型}-{来源对象}-{NNN}
```

示例：

```text
FN-EX-CAHT-BIZ-DAG-001
FN-EX-CAHT-VAL-DAG-003
FN-EX-CAHT-AUTH-DAG-002
FN-EX-CAHT-DATA-DAG-006
FN-EX-CAHT-SAFE-DAG-002
FN-EX-CAHT-SYS-DAG-001
FN-EX-CAHT-SYS-MQS-001
```

字段说明：

| 段位    | 示例            | 含义                               |
| ----- | ------------- | -------------------------------- |
| `FN`  | `FN`          | 固定前缀                             |
| 应用简称  | `EX`          | 应用简称                             |
| 功能模块  | `CAHT`        | ChatService / Chat 模块简称          |
| 错误码类型 | `SYS`         | 错误或控制事件类型                        |
| 来源对象  | `DAG` / `MQS` | DomainAgent、Supervisor 或具体外部系统简称 |
| 码号    | `001`         | 三位数字编码                           |

说明：

```text
DomainAgent 自身返回的拒答、澄清、审批、数据安全事实，来源对象使用 DAG。
外部依赖异常使用具体外部系统简称，例如 MQS、LLM、MCP、A2A、EDM。
```

---

## 4. 错误码类型

| 错误码类型  | 含义         | 适用场景                            |
| ------ | ---------- | ------------------------------- |
| `BIZ`  | 业务拒答类      | 领域不匹配、能力未开放、业务条件不满足             |
| `VAL`  | 输入校验 / 澄清类 | 意图不清、上下文不足、缺少必填参数、参数格式错误        |
| `AUTH` | 认证授权类      | 未认证、无权限、权限范围过大、审批过期             |
| `DATA` | 数据访问类      | 数据不存在、数据越权、敏感字段、个人数据、批量导出、数据最小化 |
| `SAFE` | 安全合规类      | 敏感凭据、内部上下文、Prompt、工具配置、数据外传风险   |
| `SYS`  | 系统技术类      | 超时、过载、限流、依赖不可用、协议错误、解析失败        |

---

## 5. 来源对象

来源对象表示错误、拒答、澄清、审批或异常事实的来源。

| 来源对象  | 含义                          | 示例                       |
| ----- | --------------------------- | ------------------------ |
| `DAG` | DomainAgent，下游领域 Agent      | `FN-EX-CAHT-BIZ-DAG-001` |
| `SUP` | Supervisor / ChatService 主控 | `FN-EX-CAHT-SYS-SUP-001` |
| `ITD` | IntentDecision 意图决策服务       | `FN-EX-CAHT-SYS-ITD-001` |
| `MQS` | MQS 外部系统或消息服务               | `FN-EX-CAHT-SYS-MQS-001` |
| `MCP` | MCP 网关或 MCP 服务              | `FN-EX-CAHT-SYS-MCP-001` |
| `A2A` | A2A 网关或领域 Agent 调用通道        | `FN-EX-CAHT-SYS-A2A-001` |
| `LLM` | 大模型服务                       | `FN-EX-CAHT-SYS-LLM-001` |
| `EDM` | 文档服务                        | `FN-EX-CAHT-SYS-EDM-001` |
| `LTM` | 长期记忆服务                      | `FN-EX-CAHT-SYS-LTM-001` |

说明：

```text
DAG 表示 DomainAgent 自身返回的处理事实。
MQS、MCP、A2A、LLM、EDM、LTM 等表示具体依赖系统异常。
不得继续使用 AGENT 作为编码主干段位。
```

---

## 6. 码号规则

码号使用三位数字：

```text
000-999
```

推荐规则：

| 码号范围      | 用途                                 |
| --------- | ---------------------------------- |
| `000`     | 兜底、未知或未分类场景，原则上不由 DomainAgent 主动返回 |
| `001-899` | 业务、校验、授权、数据、安全、系统异常正式编码            |
| `900-999` | 预留扩展、灰度或专项场景                       |

---

# 第二部分：DomainAgent 事件规范

## 7. 事件类型 /todo 待定

DomainAgent 只返回 `agent.*` 事件。

| type                      | 含义                  | 当前 DomainAgent 是否可继续 |
| ------------------------- | ------------------- | -------------------- |
| `agent.delta`             | 正常回答增量              | 是                    |
| `agent.snapshot`          | 当前 Agent 回答快照       | 是                    |
| `agent.completed`         | 当前 Agent 输出完成       | 否                    |
| `agent.refusal`           | 当前 Agent 明确不能处理当前请求 | 通常否                  |
| `agent.input_required`    | 当前 Agent 需要用户补充信息   | 是                    |
| `agent.approval_required` | 当前 Agent 需要授权、审批或确认 | 是                    |
| `agent.error`             | 当前 Agent 技术异常       | 按 `recoverable` 判断   |

DomainAgent 不直接返回：

```text
runtime.*
message.*
run.*
```

以上事件由 ChatService/Supervisor 归一化后生成。

---

## 8. 通用字段规范

控制类事件必以下字段：

| 字段                | 类型      | 必填   | 说明                             |
| ----------------- | ------- | ---- | ------------------------------ |
| `type`            | string  | 是    | 事件类型，例如 `agent.refusal`        |
| `code`            | string  | 是    | DomainAgent 标准编码               |
| `agentId`         | string  | 是    | 当前领域 Agent 标识                  |
| `reasonCode`      | string  | 是    | 当前 Agent 返回的机器可读原因码            |
| `recoverable`     | boolean | 是    | 当前事件是否可在当前 DomainAgent 内继续恢复处理 |
| `eventId`         | string  | 否    | 下游事件 ID，用于排障和幂等                |
| `traceId`         | string  | 否    | 链路追踪 ID                        |
| `timestamp`       | string  | 否    | ISO-8601 时间                    |
| `reason`          | string  | 否    | 面向排障的简短原因，不得包含敏感数据             |
| `metadata`        | object  | 否    | 非敏感扩展信息                        |
| `userMessage`     | string  | 否    | 候选展示文案，Supervisor 可统一覆盖        |
| `inputRequest`    | object  | 条件必填 | `agent.input_required` 必填      |
| `approvalRequest` | object  | 条件必填 | `agent.approval_required` 必填   |

---

## 9. recoverable 字段定义

`recoverable` 只表示当前 DomainAgent 内是否可以继续恢复处理。

```text
recoverable = 当前 DomainAgent 是否可以在补充信息、授权确认或技术恢复后继续处理
```

它不表示：

```text
系统是否最终拒答
系统是否重新路由
系统是否升级 Runtime
系统是否结束当前 run
```

### 9.1 recoverable=true

表示当前 DomainAgent 可以在后续条件满足后继续处理。

| 场景                | 事件类型                      | recoverable |
| ----------------- | ------------------------- | ----------- |
| 缺少必填参数            | `agent.input_required`    | true        |
| 用户意图不明确           | `agent.input_required`    | true        |
| 缺少查询时间范围          | `agent.input_required`    | true        |
| 数据范围过大，需要缩小范围     | `agent.input_required`    | true        |
| 需要授权确认            | `agent.approval_required` | true        |
| 需要审批确认            | `agent.approval_required` | true        |
| 当前 Agent 临时超时且可重试 | `agent.error`             | true        |

### 9.2 recoverable=false

表示当前 DomainAgent 无法在自身范围内继续处理。

| 场景                   | 事件类型            | recoverable |
| -------------------- | --------------- | ----------- |
| 当前请求不在该 Agent 领域范围内  | `agent.refusal` | false       |
| 当前 Agent 未开放该能力      | `agent.refusal` | false       |
| 当前用户无权限              | `agent.refusal` | false       |
| 请求敏感凭据               | `agent.refusal` | false       |
| 请求内部 Prompt 或工具配置    | `agent.refusal` | false       |
| 请求跨租户或跨组织数据          | `agent.refusal` | false       |
| 请求批量导出受限数据           | `agent.refusal` | false       |
| 当前 Agent 响应协议不符合契约要求 | `agent.error`   | false       |

注意：

```text
recoverable=false 只表示当前 DomainAgent 无法继续处理。
Supervisor 仍可基于 code 判断系统后续动作。
```

---

# 第三部分：编码清单

## 10. 业务拒答类 BIZ

| code                     | reasonCode               | type            | recoverable | 标准展示文案                 |
| ------------------------ | ------------------------ | --------------- | ----------- | ---------------------- |
| `FN-EX-CAHT-BIZ-DAG-001` | `OUT_OF_DOMAIN`          | `agent.refusal` | false       | 当前请求不在该领域 Agent 处理范围内。 |
| `FN-EX-CAHT-BIZ-DAG-002` | `UNSUPPORTED_CAPABILITY` | `agent.refusal` | false       | 当前领域 Agent 未开放该处理能力。   |

说明：

```text
BIZ-DAG 类编码只表达当前 DomainAgent 的领域范围或能力边界。
Supervisor 可以基于该编码判断后续系统动作。
DomainAgent 不返回路由目标，不返回路由动作。
```

---

## 11. 输入校验 / 澄清类 VAL

| code                     | reasonCode              | type                   | recoverable | 标准展示文案              |
| ------------------------ | ----------------------- | ---------------------- | ----------- | ------------------- |
| `FN-EX-CAHT-VAL-DAG-001` | `AMBIGUOUS_INTENT`      | `agent.input_required` | true        | 当前请求意图不明确，需要补充处理目标。 |
| `FN-EX-CAHT-VAL-DAG-002` | `MISSING_CONTEXT`       | `agent.input_required` | true        | 当前请求缺少必要上下文。        |
| `FN-EX-CAHT-VAL-DAG-003` | `MISSING_REQUIRED_SLOT` | `agent.input_required` | true        | 当前请求缺少必填业务参数。       |
| `FN-EX-CAHT-VAL-DAG-004` | `INVALID_INPUT_FORMAT`  | `agent.input_required` | true        | 当前请求参数格式不符合处理要求。    |

说明：

```text
VAL-DAG 类编码用于当前 DomainAgent 的多轮澄清。
Supervisor 不应将其理解为当前领域失败。
```

---

## 12. 认证、授权与审批类 AUTH

| code                      | reasonCode             | type                      | recoverable | 标准展示文案               |
| ------------------------- | ---------------------- | ------------------------- | ----------- | -------------------- |
| `FN-EX-CAHT-AUTH-DAG-001` | `AUTH_REQUIRED`        | `agent.approval_required` | true        | 当前会话未完成身份认证或授权状态已失效。 |
| `FN-EX-CAHT-AUTH-DAG-002` | `PERMISSION_DENIED`    | `agent.refusal`           | false       | 当前用户不具备该操作权限。        |
| `FN-EX-CAHT-AUTH-DAG-003` | `PERMISSION_TOO_BROAD` | `agent.approval_required` | true        | 请求范围超过当前默认授权范围。      |
| `FN-EX-CAHT-AUTH-DAG-004` | `APPROVAL_REQUIRED`    | `agent.approval_required` | true        | 当前操作需要完成授权确认。        |
| `FN-EX-CAHT-AUTH-DAG-005` | `APPROVAL_EXPIRED`     | `agent.approval_required` | true        | 当前授权确认已过期。           |

说明：

```text
AUTH-DAG 类中的 agent.approval_required 用于当前 DomainAgent 的授权或审批流程。
授权或审批完成后，可以继续当前 DomainAgent。
PERMISSION_DENIED 表示当前用户不具备权限，DomainAgent 不继续处理。
```

---

## 13. 数据访问类 DATA

| code                      | reasonCode                   | type                                        | recoverable  | 标准展示文案                |
| ------------------------- | ---------------------------- | ------------------------------------------- | ------------ | --------------------- |
| `FN-EX-CAHT-DATA-DAG-001` | `DATA_NOT_FOUND`             | `agent.refusal` / `agent.input_required`    | false / true | 未查询到符合条件的数据。          |
| `FN-EX-CAHT-DATA-DAG-002` | `DATA_SCOPE_VIOLATION`       | `agent.refusal`                             | false        | 请求的数据范围超出当前用户授权范围。    |
| `FN-EX-CAHT-DATA-DAG-003` | `SENSITIVE_FIELD_RESTRICTED` | `agent.refusal` / `agent.approval_required` | false / true | 请求包含受限敏感字段。           |
| `FN-EX-CAHT-DATA-DAG-004` | `PERSONAL_DATA_RESTRICTED`   | `agent.refusal` / `agent.approval_required` | false / true | 请求包含受限个人数据。           |
| `FN-EX-CAHT-DATA-DAG-005` | `BULK_EXPORT_RESTRICTED`     | `agent.refusal`                             | false        | 当前请求触发批量数据导出限制。       |
| `FN-EX-CAHT-DATA-DAG-006` | `DATA_MINIMIZATION_REQUIRED` | `agent.input_required`                      | true         | 当前请求的数据范围过大，需要明确查询范围。 |
| `FN-EX-CAHT-DATA-DAG-007` | `CROSS_TENANT_DATA_ACCESS`   | `agent.refusal`                             | false        | 请求涉及跨租户或跨组织数据访问。      |

说明：

```text
DATA-DAG 类编码用于表达当前 DomainAgent 识别到的数据访问事实。
DATA_MINIMIZATION_REQUIRED 可通过补充范围继续当前 DomainAgent。
敏感字段、个人数据如可通过授权继续，则使用 agent.approval_required。
如不可授权继续，则使用 agent.refusal。
```

---

## 14. 安全合规类 SAFE

| code                      | reasonCode                        | type            | recoverable | 标准展示文案                       |
| ------------------------- | --------------------------------- | --------------- | ----------- | ---------------------------- |
| `FN-EX-CAHT-SAFE-DAG-001` | `POLICY_RESTRICTED`               | `agent.refusal` | false       | 当前请求未通过安全合规校验。               |
| `FN-EX-CAHT-SAFE-DAG-002` | `SENSITIVE_INFO_REQUESTED`        | `agent.refusal` | false       | 请求涉及凭据或敏感认证信息，已拒绝处理。         |
| `FN-EX-CAHT-SAFE-DAG-003` | `INTERNAL_CONTEXT_REQUESTED`      | `agent.refusal` | false       | 请求涉及内部上下文或系统配置信息，已拒绝处理。      |
| `FN-EX-CAHT-SAFE-DAG-004` | `PROMPT_OR_TOOL_CONFIG_REQUESTED` | `agent.refusal` | false       | 请求涉及系统提示词、工具配置或内部执行参数，已拒绝处理。 |
| `FN-EX-CAHT-SAFE-DAG-005` | `DATA_EXFILTRATION_RISK`          | `agent.refusal` | false       | 当前请求存在数据外传风险，已拒绝处理。          |
| `FN-EX-CAHT-SAFE-DAG-006` | `UNSAFE_INSTRUCTION`              | `agent.refusal` | false       | 当前请求未通过指令安全校验。               |

说明：

```text
SAFE-DAG 类编码用于表达安全与合规拒答事实。
DomainAgent 不继续处理当前请求。
Supervisor 基于该类编码执行统一安全处置。
```

---

## 15. 系统技术类 SYS：DomainAgent 自身

| code                     | reasonCode                      | type          | recoverable | 标准展示文案                        |
| ------------------------ | ------------------------------- | ------------- | ----------- | ----------------------------- |
| `FN-EX-CAHT-SYS-DAG-001` | `AGENT_OVERLOADED`              | `agent.error` | true        | 领域 Agent 处理资源过载，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-DAG-002` | `DOMAIN_AGENT_TIMEOUT`          | `agent.error` | true        | 领域 Agent 处理超时，本次请求未完成。稍后重试。   |
| `FN-EX-CAHT-SYS-DAG-003` | `DOMAIN_AGENT_RATE_LIMITED`     | `agent.error` | true        | 当前请求触发领域 Agent 限流控制。稍后重试。     |
| `FN-EX-CAHT-SYS-DAG-004` | `DOMAIN_AGENT_EXECUTION_FAILED` | `agent.error` | true        | 领域 Agent 执行异常，本次请求未完成。        |
| `FN-EX-CAHT-SYS-DAG-005` | `PROTOCOL_INVALID`              | `agent.error` | false       | 领域 Agent 响应协议不符合契约要求。         |
| `FN-EX-CAHT-SYS-DAG-006` | `RESPONSE_PARSE_FAILED`         | `agent.error` | false       | 领域 Agent 响应解析失败。              |

说明：

```text
SYS-DAG 类编码表示 DomainAgent 自身技术异常。
如果是具体外部依赖异常，应使用对应外部系统简称作为来源对象，例如 MQS、MCP、LLM。
```

---

## 16. 系统技术类 SYS：外部依赖示例

### 16.1 MQS

| code                     | reasonCode             | type          | recoverable | 标准展示文案                   |
| ------------------------ | ---------------------- | ------------- | ----------- | ------------------------ |
| `FN-EX-CAHT-SYS-MQS-001` | `MQS_UNAVAILABLE`      | `agent.error` | true        | MQS 服务不可用，本次请求未完成。稍后重试。  |
| `FN-EX-CAHT-SYS-MQS-002` | `MQS_TIMEOUT`          | `agent.error` | true        | MQS 服务调用超时，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-MQS-003` | `MQS_RATE_LIMITED`     | `agent.error` | true        | 当前请求触发 MQS 服务限流控制。稍后重试。  |
| `FN-EX-CAHT-SYS-MQS-004` | `MQS_RESPONSE_INVALID` | `agent.error` | false       | MQS 服务响应不符合处理要求。         |

### 16.2 LLM

| code                     | reasonCode             | type          | recoverable | 标准展示文案                  |
| ------------------------ | ---------------------- | ------------- | ----------- | ----------------------- |
| `FN-EX-CAHT-SYS-LLM-001` | `LLM_UNAVAILABLE`      | `agent.error` | true        | 大模型服务不可用，本次请求未完成。稍后重试。  |
| `FN-EX-CAHT-SYS-LLM-002` | `LLM_TIMEOUT`          | `agent.error` | true        | 大模型服务调用超时，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-LLM-003` | `LLM_CONTEXT_EXCEEDED` | `agent.error` | false       | 当前请求上下文长度超出处理限制。        |

### 16.3 MCP

| code                     | reasonCode        | type          | recoverable | 标准展示文案                   |
| ------------------------ | ----------------- | ------------- | ----------- | ------------------------ |
| `FN-EX-CAHT-SYS-MCP-001` | `MCP_UNAVAILABLE` | `agent.error` | true        | MCP 服务不可用，本次请求未完成。稍后重试。  |
| `FN-EX-CAHT-SYS-MCP-002` | `MCP_TIMEOUT`     | `agent.error` | true        | MCP 服务调用超时，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-MCP-003` | `MCP_TOOL_FAILED` | `agent.error` | true        | MCP 工具执行异常，本次请求未完成。      |

### 16.4 A2A

| code                     | reasonCode             | type          | recoverable | 标准展示文案                   |
| ------------------------ | ---------------------- | ------------- | ----------- | ------------------------ |
| `FN-EX-CAHT-SYS-A2A-001` | `A2A_UNAVAILABLE`      | `agent.error` | true        | A2A 服务不可用，本次请求未完成。稍后重试。  |
| `FN-EX-CAHT-SYS-A2A-002` | `A2A_TIMEOUT`          | `agent.error` | true        | A2A 服务调用超时，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-A2A-003` | `A2A_RESPONSE_INVALID` | `agent.error` | false       | A2A 服务响应不符合处理要求。         |

### 16.5 EDM

| code                     | reasonCode               | type          | recoverable | 标准展示文案                     |
| ------------------------ | ------------------------ | ------------- | ----------- | -------------------------- |
| `FN-EX-CAHT-SYS-EDM-001` | `EDM_UNAVAILABLE`        | `agent.error` | true        | EDM 文档服务不可用，本次请求未完成。稍后重试。  |
| `FN-EX-CAHT-SYS-EDM-002` | `EDM_TIMEOUT`            | `agent.error` | true        | EDM 文档服务调用超时，本次请求未完成。稍后重试。 |
| `FN-EX-CAHT-SYS-EDM-003` | `EDM_DOCUMENT_NOT_FOUND` | `agent.error` | false       | 未查询到指定文档。                  |

---

# 第四部分：事件格式

## 17. agent.refusal

`agent.refusal` 表示当前 DomainAgent 明确不能处理当前请求。

示例：

```json
{
  "type": "agent.refusal",
  "code": "FN-EX-CAHT-BIZ-DAG-001",
  "agentId": "tax-agent",
  "traceId": "trace_xxx",
  "reasonCode": "OUT_OF_DOMAIN",
  "reason": "request outside current domain agent",
  "recoverable": false
}
```

适用场景：

```text
当前请求不在本领域范围内
当前领域 Agent 未开放该能力
当前用户无权限
请求数据越权
请求敏感凭据
请求内部上下文或系统配置
请求触发安全合规限制
```

说明：

```text
agent.refusal 只表示当前 DomainAgent 拒绝处理。
是否重新路由、是否最终拒答、是否升级 Runtime，由 Supervisor 基于 code 判断。
```

---

## 18. agent.input_required

`agent.input_required` 表示当前 DomainAgent 需要用户补充信息后继续处理。

示例：

```json
{
  "type": "agent.input_required",
  "code": "FN-EX-CAHT-VAL-DAG-003",
  "agentId": "expense-agent",
  "traceId": "trace_xxx",
  "reasonCode": "MISSING_REQUIRED_SLOT",
  "reason": "missing expenseNo or period",
  "recoverable": true,
  "inputRequest": {
    "inputType": "MISSING_SLOT",
    "missingSlots": ["expenseNo", "period"],
    "minRequired": 1,
    "questions": [
      {
        "id": "expenseNo",
        "label": "报销单号",
        "type": "text",
        "required": false
      },
      {
        "id": "period",
        "label": "报销时间范围",
        "type": "date_range",
        "required": false
      }
    ]
  }
}
```

说明：

```text
agent.input_required 不表示当前 Agent 退出。
Supervisor 可以将其转换为澄清交互。
用户补充信息后，可以继续回到当前 DomainAgent 处理。
```

### 18.1 inputRequest 字段

| 字段             | 类型     | 必填 | 说明          |
| -------------- | ------ | -- | ----------- |
| `inputType`    | string | 是  | 输入类型        |
| `missingSlots` | array  | 否  | 缺失槽位编码      |
| `minRequired`  | number | 否  | 至少需要补充的字段数量 |
| `questions`    | array  | 是  | 澄清问题列表      |

`inputType` 枚举：

| inputType           | 含义       |
| ------------------- | -------- |
| `CLARIFICATION`     | 普通澄清     |
| `MISSING_SLOT`      | 缺少业务槽位   |
| `SCOPE_REFINEMENT`  | 需要缩小查询范围 |
| `FORMAT_CORRECTION` | 需要修正输入格式 |

### 18.2 questions[] 字段

| 字段             | 类型      | 必填 | 说明       |
| -------------- | ------- | -- | -------- |
| `id`           | string  | 是  | 问题或槽位 ID |
| `label`        | string  | 是  | 展示给用户的问题 |
| `type`         | string  | 是  | 输入类型     |
| `required`     | boolean | 是  | 是否必填     |
| `options`      | array   | 否  | 选择项      |
| `defaultValue` | any     | 否  | 默认值      |
| `placeholder`  | string  | 否  | 输入提示     |

`questions[].type` 枚举：

```text
text
number
date
date_range
single_select
multi_select
boolean
```

---

## 19. agent.approval_required

`agent.approval_required` 表示当前 DomainAgent 需要用户授权、审批或确认后继续处理。

示例：

```json
{
  "type": "agent.approval_required",
  "code": "FN-EX-CAHT-AUTH-DAG-003",
  "agentId": "finance-data-agent",
  "traceId": "trace_xxx",
  "reasonCode": "PERMISSION_TOO_BROAD",
  "reason": "requested department data scope exceeds default personal scope",
  "recoverable": true,
  "approvalRequest": {
    "approvalType": "DATA_SCOPE",
    "scope": {
      "resource": "finance_records",
      "operation": "read",
      "range": "department"
    },
    "riskLevel": "MEDIUM",
    "expiresInSeconds": 300
  }
}
```

说明：

```text
agent.approval_required 不表示当前 Agent 退出。
Supervisor 可以将其转换为授权或审批交互。
用户完成授权或审批后，可以继续回到当前 DomainAgent 处理。
```

### 19.1 approvalRequest 字段

| 字段                 | 类型     | 必填 | 说明            |
| ------------------ | ------ | -- | ------------- |
| `approvalType`     | string | 是  | 审批类型          |
| `scope`            | object | 是  | 请求授权的资源、动作和范围 |
| `riskLevel`        | string | 是  | 风险等级          |
| `expiresInSeconds` | number | 否  | 授权请求有效期       |
| `approvalId`       | string | 否  | 审批请求 ID       |

`approvalType` 枚举：

| approvalType          | 含义           |
| --------------------- | ------------ |
| `DATA_SCOPE`          | 扩大数据范围       |
| `SENSITIVE_FIELD`     | 访问敏感字段       |
| `EXTERNAL_ACTION`     | 调用外部系统动作     |
| `WRITE_OPERATION`     | 写入、修改或提交业务数据 |
| `HIGH_RISK_OPERATION` | 高风险操作        |

`riskLevel` 枚举：

```text
LOW
MEDIUM
HIGH
CRITICAL
```

---

## 20. agent.error

`agent.error` 只用于当前 DomainAgent 技术异常。

示例：

```json
{
  "type": "agent.error",
  "code": "FN-EX-CAHT-SYS-DAG-001",
  "agentId": "tax-agent",
  "traceId": "trace_xxx",
  "reasonCode": "AGENT_OVERLOADED",
  "reason": "agent resource overloaded",
  "recoverable": true
}
```

外部依赖异常示例：

```json
{
  "type": "agent.error",
  "code": "FN-EX-CAHT-SYS-MQS-001",
  "agentId": "finance-data-agent",
  "traceId": "trace_xxx",
  "reasonCode": "MQS_UNAVAILABLE",
  "reason": "mqs dependency unavailable",
  "recoverable": true
}
```

使用规则：

```text
业务拒答不得使用 agent.error。
技术异常不得伪装成 agent.refusal。
是否重试、降级或结束当前 run，由 Supervisor 基于 code 判断。
```

---