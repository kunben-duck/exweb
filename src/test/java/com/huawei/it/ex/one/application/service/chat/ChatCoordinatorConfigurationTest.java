package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ChatCoordinatorConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class)
                    .withBean(SessionApplicationService.class,
                            () -> mock(SessionApplicationService.class))
                    .withBean(MemoryApplicationService.class,
                            () -> mock(MemoryApplicationService.class))
                    .withBean(RuntimeBindingApplicationService.class,
                            () -> mock(RuntimeBindingApplicationService.class))
                    .withBean(RouteSignalApplicationService.class,
                            () -> mock(RouteSignalApplicationService.class))
                    .withBean(IntentRecognitionRecordService.class,
                            () -> mock(IntentRecognitionRecordService.class))
                    .withBean(SystemResponseExecutor.class,
                            () -> mock(SystemResponseExecutor.class))
                    .withBean(AgentRuntimeExecutor.class,
                            () -> mock(AgentRuntimeExecutor.class))
                    .withBean(DocumentFacade.class,
                            () -> mock(DocumentFacade.class))
                    .withBean(ChatStreamApplicationService.class,
                            () -> mock(ChatStreamApplicationService.class))
                    .withBean(ChatRunApplicationService.class,
                            () -> mock(ChatRunApplicationService.class))
                    .withBean(ChatRunLeaseApplicationService.class,
                            () -> mock(ChatRunLeaseApplicationService.class))
                    .withBean(ChatDeltaCoalescer.class,
                            () -> mock(ChatDeltaCoalescer.class))
                    .withBean(LocalChatRunExecutionRegistry.class,
                            () -> mock(LocalChatRunExecutionRegistry.class))
                    .withBean(RunAdmissionControlService.class,
                            () -> mock(RunAdmissionControlService.class))
                    .withBean(ChatRunStopCoordinator.class,
                            () -> mock(ChatRunStopCoordinator.class))
                    .withBean(ChatInteractionApplicationService.class,
                            () -> mock(ChatInteractionApplicationService.class))
                    .withBean(ChatRunTerminalCommitService.class,
                            () -> mock(ChatRunTerminalCommitService.class))
                    .withBean(IdGenerator.class, () -> mock(IdGenerator.class))
                    .withBean(RouteMemoryApplicationService.class,
                            () -> mock(RouteMemoryApplicationService.class))
                    .withBean(ChatRunAdmissionCommitService.class,
                            () -> mock(ChatRunAdmissionCommitService.class))
                    .withBean(ChatEventBatcher.class,
                            () -> mock(ChatEventBatcher.class))
                    .withBean(ChatRunOperationalProperties.class,
                            ChatRunOperationalProperties::new)
                    .withBean(DomainAgentProperties.class, DomainAgentProperties::new)
                    .withBean(AgentDataPersistenceGate.class,
                            () -> mock(AgentDataPersistenceGate.class))
                    .withBean(
                            "chatStreamEventScheduler",
                            Scheduler.class,
                            Schedulers::immediate)
                    .withBean(
                            "domainAgentControlIoScheduler",
                            Scheduler.class,
                            Schedulers::immediate);

    @Test
    void assemblesOneFacadeAndOneCoordinatorGraphWithoutCycles() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FinanceChatFacade.class);
            assertThat(context.getBean(FinanceChatFacade.class))
                    .isInstanceOf(FinanceEXChatService.class);
            assertThat(context).hasSingleBean(FinanceChatOrchestrator.class);
            assertThat(context).hasSingleBean(ChatRuntimeDispatchCoordinator.class);
            assertThat(context).hasSingleBean(ChatEventPipeline.class);
            assertThat(context).hasSingleBean(DomainAgentRefusalCoordinator.class);
            assertThat(context).hasSingleBean(InteractionRunCoordinator.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ChatFlowFoundationConfiguration.class,
            ChatEventCoordinatorConfiguration.class,
            ChatRuntimeCoordinatorConfiguration.class,
            FinanceEXChatService.class
    })
    static class TestConfiguration {
    }
}
