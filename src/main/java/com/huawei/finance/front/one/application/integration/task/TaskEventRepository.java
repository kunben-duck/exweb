package com.huawei.finance.front.one.application.integration.task;

import com.huawei.finance.front.one.domain.task.TaskEvent;

/**
 * 任务事件持久化端口。
 */
public interface TaskEventRepository {
    /**
     * 保存任务状态变化事件。
     *
     * @param event 任务事件。
     */
    void save(TaskEvent event);
}
