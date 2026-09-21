package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.rcdis.agent.agent.RecordingToolCallback;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ChatHistoryService;

class RecordingToolCallbackTests {

    @Test
    void contextFreeInvocationFailsClosedWithoutCallingDelegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        RecordingToolCallback callback = new RecordingToolCallback(
                delegate, mock(ChatHistoryService.class), mock(AgentToolAuthorizationService.class));

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ToolContext");
        verify(delegate, never()).call("{}");
    }
}
