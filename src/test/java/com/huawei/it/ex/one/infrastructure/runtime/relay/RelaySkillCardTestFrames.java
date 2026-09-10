/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.relay;

/** 两种技能广播使用相同样例验证实时映射、历史保存及分享，避免只测试手工构造的 CARD。 */
public final class RelaySkillCardTestFrames {
    public static final String URL_CARD = """
            {
              "type": "skill-card-broadcast",
              "parent_instance_id": "domain_expert_agent_test",
              "cardType": "url",
              "cardSources": ["cardUrl"],
              "cardUrl": "https://cards.example.test/index.js",
              "intent": "ActuaryCaseCheck",
              "domainAgentId": "skill-test",
              "isSkillDiy": true,
              "session_id": "relay-session-1",
              "timestamp": "2026-09-10T22:51:41.051440",
              "version_id": 10,
              "metadata": {"authorization": "secret", "optional": null}
            }
            """;

    public static final String SCENE_CARD = """
            {
              "type": "skill-card-broadcast",
              "parent_instance_id": "domain_expert_agent_test",
              "message": "请勾选要生成案例文章的作战任务：",
              "min_selections": 1,
              "diyCardScene": {
                "stepName": "selectTasks",
                "dataList": [
                  {"item_id": "T002", "title": "容量优化", "uri": "task:2",
                   "item_type": "generic", "selected": false,
                   "metadata": {"taskStatusCn": "已完成", "creationDate": "2026-09-09"}},
                  {"item_id": "T001", "title": "应急响应", "uri": "task:1",
                   "item_type": "generic", "selected": true,
                   "metadata": {"taskStatusCn": "处理中", "creationDate": "2026-09-10"}}
                ]
              },
              "agent_name": "domain_expert_agent",
              "cardType": "diyCardScene",
              "cardSources": ["diyCardScene"],
              "isSkillDiy": true,
              "session_id": "relay-session-1",
              "timestamp": "2026-09-10T22:51:41.054127",
              "version_id": 11,
              "metadata": {"authorization": "secret", "optional": null}
            }
            """;

    private RelaySkillCardTestFrames() {
    }
}
