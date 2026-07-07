# 意图识别服务接入说明

本文档说明财经意图识别服务的独立调用方式，用于后端 adapter 联调、问题排查和意图识别效果验证。

## 使用场景

意图识别服务用于把用户问题识别为可路由的业务能力，并返回候选意图、置信度和绑定资源 ID。ChatService 可根据返回结果和置信度阈值决定是否路由到对应能力。

典型场景包括：

- 首轮问题识别，例如“查询今年客户的总利润是多少”。
- DomainAgent 或其他能力拒答后，带上下文重新识别。
- 澄清完成后，把合并后的用户真实诉求重新送入意图服务。
- 排查意图识别效果，打开 trace 查看服务内部提示词和遍历过程。

## 调用地址

```text
POST {INTENT_API_URL}
```

测试环境示例：

```text
http://kweuat.huawei.com/intent-recognition-configuration/getIntentResult
```

动态 Token 获取地址：

```text
http://kwe-beta.huawei.com/ApiCommonQuery/appToken/getRestAppDynamicToken
```

## 鉴权方式

调用意图服务前，需要先通过应用 ID 和静态 Token 获取动态 Token，然后把动态 Token 放入 `Authorization` 请求头。

```http
Content-Type: application/json
Authorization: {dynamic_token}
```

注意：

- 不要在代码仓库中提交真实静态 Token。
- `verify=false` 只建议用于本地或测试环境排查证书问题，生产环境应按企业证书规范处理。
- `options.trace=true` 会返回详细链路信息，适合排障；生产常态调用建议按性能和安全要求关闭。

## 请求体

```json
{
  "accessName": "y_11112",
  "query": "用户是想看支付成功率下降后怎么处理",
  "userId": "00859938",
  "conversationContext": {
    "routeTrigger": "domain_reject",
    "lastIntentRejectReason": {
      "lastIntent": "财经深度研究",
      "domainRejectMessage": "深度研究无法给出支付成功率下降处理相关的内容，返回主入口重新决策选择其他能力"
    },
    "history": [
      {
        "type": "route",
        "query": "查一下 3 月 19 到 20 号各渠道支付成功率有没有明显下降",
        "intent": "财经智能问数"
      },
      {
        "type": "route",
        "query": "支付成功率这个指标口径是怎么算的？",
        "intent": "财经知识助手"
      },
      {
        "type": "route",
        "query": "那广东为什么会下降？",
        "intent": "财经深度研究"
      },
      {
        "type": "clarify",
        "query": "用户是想看支付成功率下降后怎么处理"
      }
    ]
  },
  "options": {
    "trace": true
  }
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `accessName` | string | 是 | 意图服务访问入口标识。 |
| `query` | string | 是 | 本次需要识别的用户问题。澄清后建议传合并后的真实问题。 |
| `userId` | string | 是 | 当前系统归属用户标识，ChatService 侧优先取 `UserContext.globalUserId`，为空时回退 `UserContext.userId`。 |
| `conversationContext` | object | 否 | 会话上下文，用于多轮路由、拒答重路由或澄清后识别。 |
| `conversationContext.routeTrigger` | string | 否 | 路由触发原因，例如 `domain_reject`。 |
| `conversationContext.lastIntentRejectReason` | object | 否 | 上一次意图或能力拒答原因。 |
| `conversationContext.history` | array | 否 | 历史路由、澄清和上下文摘要。 |
| `options.trace` | boolean | 否 | 是否返回意图服务内部 trace 信息。 |

### history 类型

`history` 中常见类型：

| type | 说明 |
| --- | --- |
| `route` | 已发生的路由记录，通常包含 `query` 和 `intent`。 |
| `clarify` | 澄清记录。可以传合并后的 `query`，也可以传 `question` 和 `answer` 保留澄清过程。 |

澄清已合并时：

```json
{
  "type": "clarify",
  "query": "用户是想看支付成功率下降后怎么处理"
}
```

澄清未合并、需要保留过程时：

```json
{
  "type": "clarify",
  "query": "看下方案",
  "question": "你是想继续分析支付成功率下降后的处理方案，还是查询业务/项目方案？",
  "answer": "我是想看支付成功率下降后怎么处理"
}
```

## 响应体

成功响应示例：

```json
{
  "code": 200,
  "status": "success",
  "data": {
    "status": "success",
    "message": "success",
    "result": {
      "items": [
        {
          "confidence": 0.95,
          "intentId": "98989898dffd888df88789",
          "intentName": "财经智能问答",
          "resourceInstruction": {
            "resourceId": "FIN-SKL-88888888"
          },
          "score": null,
          "source": "llm"
        }
      ],
      "message": "[用户问题]...\n[识别结果]匹配成功: 财经智能问答;\n"
    }
  }
}
```

### 关键响应字段

| 字段 | 说明 |
| --- | --- |
| `code` | HTTP 业务状态码，`200` 表示成功。 |
| `status` | 顶层状态，成功时为 `success`。 |
| `data.status` | 数据层状态，成功时为 `success`。 |
| `data.result.items[]` | 候选意图列表。 |
| `items[].confidence` | 置信度，ChatService 应结合配置阈值判断是否采纳。 |
| `items[].intentId` | 意图 ID。 |
| `items[].intentName` | 意图名称。 |
| `items[].resourceInstruction.resourceId` | 意图绑定的资源 ID，可作为路由目标。 |
| `items[].source` | 识别来源，例如 `llm`。 |
| `data.trace` | `options.trace=true` 时返回的调试链路信息。 |

## Python 调用示例

```python
"""独立的“用户问题 -> 意图结果”调用示例。

本示例不引用项目内 Python 文件，只依赖 pyxis 和 requests。
使用前请替换配置常量，不要提交真实静态 Token。
"""

import requests
from pyxis.authorization.his_authorization import get_dynamic_token


TOKEN_URL = "http://kwe-beta.huawei.com/ApiCommonQuery/appToken/getRestAppDynamicToken"
INTENT_API_URL = "http://kweuat.huawei.com/intent-recognition-configuration/getIntentResult"

APP_ID = "S00000000000000000000000000000961"
STATIC_TOKEN = "<replace-with-static-token>"
REQUEST_TIMEOUT = 30


def recognize_intent(access_name: str, question: str, user_id: str, conversation_context: dict) -> dict:
    dynamic_token = get_dynamic_token(
        url=TOKEN_URL,
        app_id=APP_ID,
        static_token=STATIC_TOKEN,
    )

    response = requests.post(
        INTENT_API_URL,
        headers={
            "Content-Type": "application/json",
            "Authorization": dynamic_token,
        },
        json={
            "accessName": access_name,
            "query": question,
            "userId": user_id,
            "conversationContext": conversation_context,
            "options": {
                "trace": True,
            },
        },
        timeout=REQUEST_TIMEOUT,
        verify=False,
    )
    response.raise_for_status()

    result = response.json()
    if result.get("code") != 200 or result.get("status") != "success":
        raise RuntimeError(f"意图识别失败：{result}")
    return result


def print_trace_prompts(result: dict) -> None:
    rounds = (
        result.get("data", {})
        .get("trace", {})
        .get("llmTraversal", {})
        .get("branches", [{}])[0]
        .get("rounds", [])
    )
    for round_data in rounds:
        print("---------------系统提示词--------------------")
        print(round_data.get("systemPrompt", "No systemPrompt found"))
        print("---------------用户提示词--------------------")
        print(round_data.get("userPrompt", "No userPrompt found"))
        print("---------------提示词结束--------------------")


def main() -> None:
    access_name = "y_11112"
    question = "用户是想看支付成功率下降后怎么处理"
    user_id = "00859938"
    conversation_context = {
        "routeTrigger": "domain_reject",
        "lastIntentRejectReason": {
            "lastIntent": "财经深度研究",
            "domainRejectMessage": "深度研究无法给出支付成功率下降处理相关的内容，返回主入口重新决策选择其他能力",
        },
        "history": [
            {
                "type": "route",
                "query": "查一下 3 月 19 到 20 号各渠道支付成功率有没有明显下降",
                "intent": "财经智能问数",
            },
            {
                "type": "route",
                "query": "支付成功率这个指标口径是怎么算的？",
                "intent": "财经知识助手",
            },
            {
                "type": "route",
                "query": "那广东为什么会下降？",
                "intent": "财经深度研究",
            },
            {
                "type": "clarify",
                "query": "用户是想看支付成功率下降后怎么处理",
            },
        ],
    }

    result = recognize_intent(access_name, question, user_id, conversation_context)
    print_trace_prompts(result)

    items = result.get("data", {}).get("result", {}).get("items", [])
    if not items:
        print("未识别到意图")
        return

    for item in items:
        resource_instruction = item.get("resourceInstruction") or {}
        print(f"意图名称：{item.get('intentName')}")
        print(f"置信度：{item.get('confidence')}")
        print(f"识别来源：{item.get('source')}")
        print(f"该意图绑定的资源 ID：{resource_instruction.get('resourceId')}")


if __name__ == "__main__":
    main()
```

## ChatService 接入建议

- 意图服务调用应封装在 adapter 内，应用层只依赖标准化后的 `IntentDecision`。
- 置信度阈值应配置化，例如 `financeex.intent.confidence-threshold`。
- `conversationContext` 应由后端根据当前会话状态可信组装，前端只提供用户输入和必要的澄清结果。
- trace 信息只用于排障和离线评测，不建议写入 run metadata；如需记录，应走独立的意图识别记录表并做脱敏和限长。
- 意图服务异常、超时、低置信或空结果时，应降级到默认 Runtime 路由，不阻断主聊天链路。
