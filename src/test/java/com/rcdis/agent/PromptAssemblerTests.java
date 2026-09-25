package com.rcdis.agent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import com.rcdis.agent.agent.PromptAssembler;
import com.rcdis.agent.agent.TimeContextProvider;
import com.rcdis.agent.agent.UntrustedContextPolicy;
import com.rcdis.agent.config.AgentProperties;

/**
 * Unit tests for {@link PromptAssembler}, which owns the cache-optimal ordering invariant:
 * session-stable blocks first, the volatile current-turn tail last, the current user message never
 * included here, and the authoritative time anchor placed at the very end but left unwrapped.
 */
class PromptAssemblerTests {

    private PromptAssembler assembler() {
        return new PromptAssembler(new UntrustedContextPolicy(), new TimeContextProvider(new AgentProperties()));
    }

    @Test
    void ordersStableBlocksBeforeWindowThenAttachmentThenTimeAnchorLast() {
        List<Message> prior = List.of(
                new UserMessage("prior-user-1"),
                new AssistantMessage("prior-assistant-1"));

        List<Message> messages = assembler().assemble(
                "MEM_SUMMARY", "SEM_MEMORY", prior, "ATTACH");

        // Zone B (stable) precedes Zone C (volatile window + attachment + anchor).
        assertThat(textOf(messages.get(0))).contains("MEM_SUMMARY");
        assertThat(textOf(messages.get(1))).contains("SEM_MEMORY");
        assertThat(textOf(messages.get(2))).isEqualTo("prior-user-1");
        assertThat(textOf(messages.get(3))).isEqualTo("prior-assistant-1");
        assertThat(textOf(messages.get(4))).contains("ATTACH");
        // The very last injected block is the current-time anchor.
        assertThat(textOf(messages.get(5))).contains("当前时间");
        assertThat(messages).hasSize(6);
    }

    @Test
    void wrapsDataBlocksButLeavesTimeAnchorUnwrapped() {
        List<Message> messages = assembler().assemble(
                "MEM_SUMMARY", "SEM_MEMORY", List.of(), "ATTACH");

        assertThat(textOf(messages.get(0))).contains("【不可信数据边界】");
        assertThat(textOf(messages.get(1))).contains("【不可信数据边界】");
        assertThat(textOf(messages.get(2))).contains("【不可信数据边界】"); // attachment
        // The trailing time anchor is authoritative runtime context, so it must NOT be wrapped.
        String anchor = textOf(messages.get(3));
        assertThat(anchor).contains("当前时间");
        assertThat(anchor).doesNotContain("【不可信数据边界】");
    }

    @Test
    void blankBlocksAreSkippedAndAnchorIsAlwaysPresent() {
        List<Message> messages = assembler().assemble(null, "", List.of(), null);

        assertThat(messages).hasSize(1);
        assertThat(textOf(messages.get(0))).contains("当前时间");
    }

    @Test
    void currentTurnIsNeverAddedHere() {
        List<Message> messages = assembler().assemble(null, null, List.of(new UserMessage("old")), null);

        assertThat(messages).noneMatch(message -> textOf(message).contains("本轮"));
        // All entries are either prior turns or the time anchor; the current user text is supplied
        // separately by the caller via ChatClient .user(...).
        assertThat(messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .filter(m -> !textOf(m).contains("当前时间")))
                .extracting(this::textOf)
                .containsExactly("old");
    }

    private String textOf(Message message) {
        return message.getMessageType() == MessageType.USER
                ? ((UserMessage) message).getText()
                : ((AssistantMessage) message).getText();
    }
}
