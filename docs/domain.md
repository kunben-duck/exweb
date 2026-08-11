一、后端接入指南目标
1.1  让业务系统了解Agent接入除了前端适配外，后端这集成这块需要做哪些事项，以及如何来做
1.2 定义集成接口规范，标准化  

二、整体交互时序图

集成事项：
2.1 、提供意图描述，关键词、样例等
【例子】：
     **智能问数**
    • 目标：回答涉及数值、统计、趋势、对比、排名、报表等数据查询。
    • 典型关键词：多少、预测、增长、下降、最多、最少、收入、销毛、成本、求和、TOP、率、占比、金额、数值、排序、降序、升序。
【示例】
用户输入：沙特24年3月无资源单工时被一层驳回最多的项目
输出：{"intentType":"智能问数"}
【输出要求】
    • 按JSON格式输出，仅输出一个服务类型（如 {"intentType":"智能问数"}、{"intentType":"知识助手"}、{"intentType":"深度研究"}或{"intentType":"News"}）；
    • 禁止输出任何解释、标点、空格、换行或额外字符。
2.2 、作业系统配置与技能配置


2.3 、API接口定义

入参（示意）
字段名称
类型
是否必填
说明
示例
sessionId
String
否
会话ID，新会话无值
"7207f4a4-f048-4434-9b7b"
query
String
是
用户请求的查询内容
"如何进行话费报销"
.........
 
 
 
 
出参（示意）
说明: 接口分为多帧返回，每帧只返回部分字段
字段名称
类型
说明
示例
contentAgent
String
思考过程与大模型回答的内容，流式返回，思考过程以<thinking>开头，</thinking>结束
首先，我需要明确报销的基本流程和要求，然后针对特殊情况提供指导。
.......
 
 
 
2.4 、卡片信息配置

三、意图开发配置---删掉？
     3.1 登录管理后台，配置意图指令（EX开发人员）---接入场景越多，如何保障意图准确度？
3.1.1  从业务方获取Agent意图的相关信息
3.1.2  登录地址：https://kweuat.huawei.com/eureka/ragWeb/#/prompt ，进入提示词管理，维护元数据、few shot
3.1.3 样例库建设（新问题与样例库相似度高于98%，跳过意图识别）

四、作业系统配置与技能配置操作--合并到配置
4.1 技能配置开发
3.1.1 登录配置后台： kweuat.huawei.com/eureka/aiassistant/#/admin/operatingSystem ，进入技能中心配置管理，录入技能名称、描述等信息
  
   4.2  作业系统配置：
3.2.1 登录配置后台：https://kweuat.huawei.com/eureka/aiassistant/#/admin/operatingSystem  ，进入中心配置管理，录入技能名称、描述等信息

五、卡片配置操作（自定义卡片场景）
   5.1 配置业务Agent卡片
4.1.1 进入配置页面 https://kweuat.huawei.com/eureka/aiassistant/#/admin/operatingSystem  ，进入卡片中心技能配置管理，录入卡片地址

六、接口规范定义
以API的方式提供对接，支持sGov认证、网关认证认证方式，对应API授权给EX项目（APPID：S00000000000000000000000000000961）
6.1 发布平台
API Mall发布（sGov）

	APIG 发布（APIG）

6.2、接口入参
 
接口入参EX会提供下列标准输入，主要覆盖场景：用户在EurekaX前端界面输入、主屏作业系统通过EurekaX通信通道为领域Agent传递作业信息；EurekaX与领域Agent数据交互：如messageid、globaluserid。
输入场景
标准字段
类型
处理逻辑
附件
attachment
list
转换为EDM ID，同时EDM会根据APPID+技能名称作为标签授权，实现对应技能只能获取对应技能相关附件。
对产品的要求：能按EDM ID获取文件，去进行附件处理
用户query
query
string
用户发送的query内容、意图指代不明的澄清信息
对产品的要求：能按query处理用户的自然语言请求，目前仅支持文本类的query
 
messageid
string
用户query生成的message
对产品的要求：按需消费用户message信息
用户工号
w3account
string
对产品的要求：按需消费标准格式w3account
用户身份
globaluserid
string
对产品的要求：按需消费标准格式globaluserid
认证信息
authorization
 
解决EurekaX可调用domainAgent API，不同认证头不一样
API网关：有些支持authorization，有些支持x-hw-id， x-hw-appkey；IAM；SOA
对产品的要求：在HUB端提供对应认证信息及密钥，同时在接收调用响应该认证
主屏页面内容（副屏场景）
metadata
 
解决主屏信息传递问题：前端传入副屏的信息，产品定义从作业系统感知到的信息。
副屏信息由EurekaX透传给DomainAgent；
对产品的要求：按标准通信格式装载信息、Agent可接收主屏/作业系统相关作业信息
 
session id
string
新建会话，生成session id
对产品的要求：按需消费用户会话标识
国际化
lang
 
语言环境：EN/CN
用户选择模式
responde_mode
 string
在HUB对技能配置是否支持模式切换，模式切换内容由EX定义，包括：Fast模式/深度思考模式（fast/deepthinking）
对产品的要求：注册声明是否需要，基于该模式不同，进行不同处理；
 

请求头按照技能配置中鉴权方式的不同具备不同的请求头：
IAM、SOA：Authorization，APIG：X-HW-ID，X-HW-APPKEY
请求体如下：

{
"attachment": [                           // 附件列表，转换为EDM ID，EDM根据APPID+技能名称标签授权
"edmid_001",
"edmid_002"
],
"query": "帮我分析一下最近的资金流向",        // 用户query内容，意图指代不明的澄清信息，目前仅支持文本类
"messageId": "msg_abc123",               // 用户query生成的message标识，按需消费
"w3Account": "00961281",                  // 用户工号，标准格式w3account
"globalUserId": "131269512",              // 用户身份标识，标准格式globaluserid
"metadata": {                            // 主屏页面内容（副屏场景），由EurekaX透传给DomainAgent
……
},
"sessionId": "session_xyz_789",           // 会话标识，新建会话时生成
"lang": "CN",                            // 国际化语言环境，枚举：EN/CN
"responseMode": "fast"                   // 用户选择模式，枚举：fast/deep，HUB端配置技能是否支持模式切换
}
 
	6.3、接口出参
出参主要覆盖2种场景：
①前端呈现给用户的信息，如思维链、答案（answer/正文）
②后端需要消费的信息，如拒答场景
前端渲染信息包括：思维链、答案（answer/正文），其中自定义卡片由接入产品方提供渲染卡片，其他场景由EurekaX提供标准渲染组件，由领域Agent提供数据

分类
子类
展示需求
标准字段
类型
图示
代码示意
产品处理逻辑
思维链
思维链整体
思考状态：开始、结束
thinkState
string
略
思维链中开始和结束的标识
思考的时间
thinkTime
string
技能在思维链完成思考和工具执行的时间
思考
思考标题：大脑图标
thinkTitle
string
思维链的思考标题
思考正文
thinkContent
string
思维链的思考正文
工具执行
工具标题：工具执行图片
toolTitle
string
工具执行的标题
工具结果：
toolMatchRes
string
工具执行的结果
答案/answer/正文
MD
流式正文（MD）
content
string
略
大模型回答的内容，流式返回
自定义卡片
是否使用自定义卡片
opencard
string
 
略
配置了自定义卡片配置后，是否想要继续使用标准卡片
opencard：N，表示用标准卡片，不使用自定义卡片
自定义卡片数据1：对象类型
diyCardScene
Object
 
略
透传给业务卡片的结构化数据，由业务系统自定义
自定义卡片数据2：string类型（卡片内部MD）
contentAgent
string
 
略
透传给业务卡片的思考过程与大模型回答的内容，流式返回，思考过程以<think>开头，</think>结束
标准卡片
推荐问
排序id
id
number
按照id升序排序
推荐问显示出来的问题
query
string
用户看到的推荐问的内容
推荐问扩展字段
metadata
object
domainAgent想要添加的扩展字段
发送或复制
type
string
推荐问是直接发送或先填充至输入框
国际化
languageCode
string
国际化：zh_CN、en_US
拒答
拒答
标记是拒答类型
type
 
 
 
标记拒答编码
code
 
 
 
拒答技能ID
agentId
 
 
 
拒答请求id，针对哪条请求拒答的
traceid
 
 
 
拒答原因编码
reasonCode
 
 
 
拒答原因
reason
 
 
 
请求是否触发安全合规限制
recoverable
 
 
 
 
响应报文示例如下：

data: {"think_state": "start", "think_title": "正在分析资金流向", "think_content": "需要先查询近30天的资金流水数据，然后按类别汇总分析..."}

data: {"think_state": "start", "think_title": "调用工具查询", "think_content": "正在调用资金流水查询接口..."}

data: {"tool_title": "查询资金流水", "tool_match_res": "{\"total\": 156, \"amount\": 2300000}"}

data: {"think_state": "stop", "think_time": "2026-08-07T10:30:05Z", "think_title": "分析完成", "think_content": "已获取数据，开始生成回答..."}

data: {"content": "根据分析，"}

data: {"content": "最近30天资金流向如下：\n\n"}

data: {"content": "| 类别 | 金额(万元) | 占比 |\n|---|---|---|\n"}

data: {"content": "| 经营性流入 | 1,523 | 66.2% |\n| 投资性流出 | 890 | 38.7% |\n| 融资性流入 | 680 | 29.6% |\n\n"}

data: {"content": "建议重点关注投资性流出占比偏高的情况。"}

data: {"id": 1, "query": "查看上月资金明细", "type": "send", "language_code": "zh_CN"}

data: {"id": 2, "query": "投资性流出详细分析", "type": "send", "language_code": "zh_CN"}

data: {"id": 3, "query": "生成资金流向报告", "type": "copy", "language_code": "zh_CN"}

data: [DONE]
 

七、集成开发准入条件
附：联调自检清单
类型
检查项
确认主体
状态
开发
业务Agent 联调环境已部署并可独立访问
业务系统
✅
开发
已完成Agent API发布
业务系统
✅
开发
EurekaX 已完成业务Agent 接入相关参数配置
EurekaX
✅
开发
双方已完成独立的功能特性的测试验证（Mock的方式）
业务系统、EurekaX
✅
开发
双方的网络通讯OK（部分Agent部署在祥云）
业务系统、EurekaX
✅
数据
Agent 元数据（名称、ID、描述）等已提供
业务系统
✅
数据
端点 URL、鉴权方式、凭证等已提供
业务系统
✅
数据
接口文档（含错误码）已提供
业务系统
✅
数据
典型测试用例已提供
业务系统、EurekaX
✅