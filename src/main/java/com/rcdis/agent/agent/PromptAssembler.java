package com.rcdis.agent.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/**
 * Owns the ordering invariant of every model-bound prompt: the more stable a block is, the closer it
 * sits to the head, so the provider's prompt cache can match the longest possible prefix.
 *
 * <p>The system message (static prompt + tool catalogue) is assembled separately in
 * {@link AgentChatClientFactory} and forms the globally static head. This assembler produces the
 * remainder of the message list, split into two zones:</p>
 * <ul>
 *   <li><b>Session-stable</b>: the compressed rolling summary / hard facts and the cross-session
 *       semantic memories. These change only when compression runs or the retrieved memory set
 *       shifts, so keeping them ahead of the volatile window widens the cacheable prefix.</li>
 *   <li><b>Current-turn volatile</b>: the recent raw history window (which already excludes the
 *       current turn), any attachments, and the per-request current-time anchor.</li>
 * </ul>
 *
 * <p>The current user turn is deliberately NOT added here; the caller appends it exactly once via
 * the ChatClient {@code .user(...)} so it never appears twice. Injected data blocks (summary,
 * memories, attachments) are wrapped by {@link UntrustedContextPolicy} so embedded text cannot be
 * mistaken for instructions; the time anchor is authoritative runtime context and is therefore not
 * wrapped.</p>
 */
@Component
@RequiredArgsConstructor
public class PromptAssembler {

    private final UntrustedContextPolicy untrustedContextPolicy;
    private final TimeContextProvider timeContextProvider;

    /**
     * Assembles the ordered message list that precedes the current user turn.
     *
     * @param memoryPrefix    compressed rolling summary + hard facts, or blank when none
     * @param semanticPrefix  cross-session semantic memory block, or blank when none
     * @param priorMessages   recent raw window, already excluding the current turn (may be empty)
     * @param attachmentContext attachment text block for this turn, or blank when none
     * @return messages in cache-optimal order (stable zones first, volatile anchor last)
     */
    public List<Message> assemble(String memoryPrefix,
                                  String semanticPrefix,
                                  List<Message> priorMessages,
                                  String attachmentContext) {
        List<Message> messages = new ArrayList<>();

        // Session-stable zone: reused across turns until compression / memory retrieval changes it.
        addWrappedData(messages, memoryPrefix);
        addWrappedData(messages, semanticPrefix);

        // Current-turn volatile zone.
        if (priorMessages != null) {
            messages.addAll(priorMessages);
        }
        addWrappedData(messages, attachmentContext);
        messages.add(new UserMessage(timeContextProvider.currentTimeAnchor().strip()));

        return messages;
    }

    private void addWrappedData(List<Message> messages, String content) {
        if (StringUtils.hasText(content)) {
            messages.add(new UserMessage(untrustedContextPolicy.wrap(content)));
        }
    }
}
