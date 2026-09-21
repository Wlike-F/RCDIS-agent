package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentChatClientFactory;
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.AgentToolRegistry;
import com.rcdis.agent.agent.ChatStreamListener;
import com.rcdis.agent.agent.ConversationLocks;
import com.rcdis.agent.agent.RecordingToolCallback;
import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.agent.UntrustedContextPolicy;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;
import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.service.AgentApplicationService;
import com.rcdis.agent.service.AgentAttachmentService;
import com.rcdis.agent.service.AgentSemanticMemoryService;
import com.rcdis.agent.service.AgentTraceService;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.to.ContextSnapshotTO;
import com.rcdis.agent.vo.AgentMemoryVO;
import com.rcdis.agent.to.ModelEndpointTO;
import com.rcdis.agent.vo.ModelProviderVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Real streaming chat pipeline.
 *
 * <p>Flow per request:</p>
 * <ol>
 *   <li>Resolve the model provider (falls back to the default when the caller sends none).</li>
 *   <li>Resolve or create the {@code chat_session} row and enforce ownership.</li>
 *   <li>Load the recent history from {@code chat_message} and persist the incoming user turn.</li>
 *   <li>Build a per-request {@link ChatClient} with the Chinese system prompt.</li>
 *   <li>Subscribe to the token flux, forwarding every chunk to the listener and accumulating it.</li>
 *   <li>Persist the assistant turn (DONE or ERROR) and release the per-conversation lock.</li>
 * </ol>
 *
 * <p>Concurrency: writes for a single {@code conversationId} are serialized by a PostgreSQL
 * advisory lock (plus a local striped lock), so multiple application instances cannot interleave
 * history reads and writes for the same conversation.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApplicationServiceImpl implements AgentApplicationService {

    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_TIMEOUT = "TIMEOUT";

    private final ModelProviderService modelProviderService;
    private final ChatHistoryService chatHistoryService;
    private final AgentChatClientFactory agentChatClientFactory;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentTraceService agentTraceService;
    private final AgentAttachmentService agentAttachmentService;
    private final ContextCompressor contextCompressor;
    private final AgentSemanticMemoryService agentSemanticMemoryService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final ConversationLocks conversationLocks;
    private final UntrustedContextPolicy untrustedContextPolicy;
    private final AgentToolAuthorizationService agentToolAuthorizationService;
    private final AgentMetrics agentMetrics;

    @Override
    public ChatResponse chat(ChatRequest request) {
        AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runPipeline(request, new ChatStreamListener() {
            @Override
            public void onToken(String token) {
                // The blocking entry point discards intermediate tokens; the final response carries
                // the full content.
            }

            @Override
            public void onComplete(ChatResponse response) {
                finalResponse.set(response);
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
            }
        });
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(
                    "CHAT_STREAM_FAILED",
                    "对话生成失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
        return finalResponse.get();
    }

    @Override
    public void streamChat(ChatRequest request, ChatStreamListener listener) {
        runPipeline(request, listener);
    }

    // ---------- pipeline ----------

    private void runPipeline(ChatRequest request, ChatStreamListener listener) {
        String conversationId = resolveConversationId(request.conversationId());
        if (!StringUtils.hasText(request.message())) {
            listener.onError(new BusinessException("CHAT_MESSAGE_BLANK", "消息内容不能为空"));
            return;
        }
        conversationLocks.runLocked(conversationId, () -> {
            ChatSessionEntity session = null;
            ModelEndpointTO endpoint = null;
            StringBuilder collected = new StringBuilder();
            TurnTraceCollector collector = new TurnTraceCollector();
            try {
                ModelProviderVO provider = modelProviderService.resolveProvider(request.providerId());
                session = chatHistoryService.resolveSession(conversationId, provider.providerId());
                endpoint = modelProviderService.resolveEndpoint(provider.providerId(), null);

                chatHistoryService.appendUserMessage(session, request.message());
                chatHistoryService.updateSessionTitleIfBlank(session, request.message());

                // Compressed prefix (rolling summary + hard facts) + recent raw window.
                ContextSnapshotTO snapshot = chatHistoryService.loadContextForModel(conversationId);
                List<Message> promptMessages = new ArrayList<>();
                String memoryPrefix = buildMemoryPrefix(snapshot);
                if (memoryPrefix != null) {
                    promptMessages.add(new UserMessage(untrustedContextPolicy.wrap(memoryPrefix)));
                }
                promptMessages.addAll(snapshot.recentMessages());
                String attachmentContext = buildAttachmentContext(request.attachmentIds(), conversationId);
                if (attachmentContext != null) {
                    promptMessages.add(new UserMessage(untrustedContextPolicy.wrap(attachmentContext)));
                }

                // Cross-session semantic memories; the read switch is gated inside the service.
                CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
                List<AgentMemoryEntity> semanticMemories =
                        agentSemanticMemoryService.loadForInjection(currentUser.userId());
                agentMetrics.recordMemoryInjection(semanticMemories.size());
                if (!semanticMemories.isEmpty()) {
                    promptMessages.add(new UserMessage(
                            untrustedContextPolicy.wrap(buildSemanticPrefix(semanticMemories))));
                }

                ChatClient chatClient = agentChatClientFactory.create(endpoint);
                AgentToolContext toolContext =
                        new AgentToolContext(listener, conversationId, currentUser, collector, session);
                StreamOutcome outcome = subscribeAndAwait(
                        chatClient, promptMessages, request.message(), collected, listener, toolContext, collector);

                if (outcome.error() != null) {
                    String status = outcome.timedOut() ? STATUS_TIMEOUT : STATUS_ERROR;
                    agentTraceService.record(collector, conversationId, endpoint.providerId(),
                            endpoint.modelName(), status, outcome.error().getMessage());
                    agentMetrics.recordTurn(endpoint.providerId(), endpoint.modelName(), status, collector);
                    chatHistoryService.appendAssistantMessage(
                            session,
                            collected.toString(),
                            endpoint.providerId(),
                            endpoint.modelName(),
                            status,
                            outcome.error().getMessage(),
                            null);
                    listener.onError(outcome.error());
                    return;
                }

                String content = collected.toString();
                agentTraceService.record(collector, conversationId, endpoint.providerId(),
                        endpoint.modelName(), AgentTurnTraceEntity.STATUS_DONE, null);
                agentMetrics.recordTurn(endpoint.providerId(), endpoint.modelName(), AgentTurnTraceEntity.STATUS_DONE, collector);
                chatHistoryService.appendAssistantMessage(
                        session,
                        content,
                        endpoint.providerId(),
                        endpoint.modelName(),
                        STATUS_DONE,
                        null,
                        null);
                log.atInfo()
                        .addKeyValue("conversationId", conversationId)
                        .addKeyValue("providerId", endpoint.providerId())
                        .addKeyValue("modelName", endpoint.modelName())
                        .addKeyValue("historyMessages", snapshot.recentMessages().size())
                        .addKeyValue("responseLength", content.length())
                        .log("Agent chat turn completed");
                listener.onComplete(new ChatResponse(
                        conversationId, content, endpoint.providerId(), endpoint.modelName()));
                contextCompressor.compressIfNeeded(conversationId);
                agentSemanticMemoryService.bumpHitsAsync(
                        semanticMemories.stream().map(AgentMemoryEntity::getId).toList());
                agentSemanticMemoryService.extractAsync(
                        currentUser.userId(), currentUser.username(), conversationId, null,
                        request.message(), content);
            } catch (RuntimeException exception) {
                log.atError()
                        .setCause(exception)
                        .addKeyValue("conversationId", conversationId)
                        .log("Agent chat turn failed before completion");
                // Persist whatever partial content we collected so the UI still shows the attempt.
                if (session != null && endpoint != null) {
                    try {
                        chatHistoryService.appendAssistantMessage(
                                session,
                                collected.toString(),
                                endpoint.providerId(),
                                endpoint.modelName(),
                                STATUS_ERROR,
                                exception.getMessage(),
                                null);
                    } catch (RuntimeException persistFailure) {
                        log.atWarn()
                                .setCause(persistFailure)
                                .addKeyValue("conversationId", conversationId)
                                .log("Failed to persist errored assistant turn");
                    }
                }
                agentTraceService.record(collector, conversationId,
                        endpoint == null ? null : endpoint.providerId(),
                        endpoint == null ? null : endpoint.modelName(),
                        AgentTurnTraceEntity.STATUS_ERROR, exception.getMessage());
                listener.onError(exception);
                contextCompressor.compressIfNeeded(conversationId);
            }
        });
    }

    private StreamOutcome subscribeAndAwait(ChatClient chatClient,
                                            List<Message> history,
                                            String userMessage,
                                            StringBuilder collected,
                                            ChatStreamListener listener,
                                            AgentToolContext toolContext,
                                            TurnTraceCollector collector) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux = chatClient.prompt()
                .messages(history.toArray(new Message[0]))
                .user(userMessage)
                .toolCallbacks(recordingToolCallbacks())
                .toolContext(AgentToolContext.asToolContextMap(toolContext))
                .stream()
                .chatResponse();
        Disposable disposable = responseFlux
                .doOnNext(chatResponse -> {
                    String token = extractText(chatResponse);
                    if (token != null && !token.isEmpty()) {
                        collector.markFirstToken();
                        collected.append(token);
                        listener.onToken(token);
                    }
                    org.springframework.ai.chat.metadata.Usage usage = chatResponse.getMetadata() == null
                            ? null
                            : chatResponse.getMetadata().getUsage();
                    if (usage != null) {
                        collector.setUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
                    }
                })
                .doOnError(error -> {
                    errorRef.set(error);
                    latch.countDown();
                })
                .doOnComplete(latch::countDown)
                .subscribe();
        try {
            boolean finished = latch.await(agentProperties.getStreamTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                disposable.dispose();
                return new StreamOutcome(new BusinessException(
                        "CHAT_STREAM_TIMEOUT",
                        "对话生成超时（超过 " + agentProperties.getStreamTimeoutSeconds() + " 秒），请重试或换用更快的模型"),
                        true);
            }
            return new StreamOutcome(errorRef.get(), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            disposable.dispose();
            return new StreamOutcome(interrupted, true);
        }
    }

    /**
     * Wraps every registered tool callback with {@link RecordingToolCallback} so each tool result
     * is persisted as a {@code role=tool} history row (facts source / recall), without touching the
     * individual {@code @Tool} methods.
     */
    private ToolCallback[] recordingToolCallbacks() {
        ToolCallback[] base = MethodToolCallbackProvider.builder()
                .toolObjects(agentToolRegistry.toolBeans().toArray())
                .build()
                .getToolCallbacks();
        ToolCallback[] wrapped = new ToolCallback[base.length];
        for (int i = 0; i < base.length; i++) {
            wrapped[i] = new RecordingToolCallback(base[i], chatHistoryService, agentToolAuthorizationService, agentMetrics);
        }
        return wrapped;
    }

    /**
     * Builds the cross-session semantic-memory system prefix, or relies on the caller to skip when
     * the list is empty.
     */
    private String buildSemanticPrefix(List<AgentMemoryEntity> memories) {
        StringBuilder sb = new StringBuilder(
                "【跨会话记忆】以下是你此前记住的、关于当前用户的长期事实（跨会话有效，供参考）：\n");
        for (AgentMemoryEntity memory : memories) {
            sb.append("- [").append(memory.getFactType()).append("] ").append(memory.getContent()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Builds the compressed-memory system prefix (rolling summary + hard facts), or null when the
     * session has no compression yet.
     */
    private String buildMemoryPrefix(ContextSnapshotTO snapshot) {
        boolean hasSummary = StringUtils.hasText(snapshot.rollingSummary());
        boolean hasFacts = StringUtils.hasText(snapshot.summaryFactsJson());
        if (!hasSummary && !hasFacts) {
            return null;
        }
        StringBuilder sb = new StringBuilder(
                "以下是本会话早期历史的压缩记忆（如需精确原文细节，可调用 recall_history 工具回源核对）：\n");
        if (hasSummary) {
            sb.append("【摘要】\n").append(snapshot.rollingSummary()).append('\n');
        }
        if (hasFacts) {
            sb.append("【硬事实要点】\n");
            try {
                List<AgentMemoryVO.SummaryFactVO> facts = objectMapper.readValue(
                        snapshot.summaryFactsJson(),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, AgentMemoryVO.SummaryFactVO.class));
                for (AgentMemoryVO.SummaryFactVO fact : facts) {
                    sb.append("- [turn ").append(fact.turnSeq()).append("] ").append(fact.text()).append('\n');
                }
            } catch (Exception ignored) {
                sb.append(snapshot.summaryFactsJson()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Builds a system-level context block from uploaded attachments, or null when none referenced.
     */
    private String buildAttachmentContext(List<Long> attachmentIds, String conversationId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return null;
        }
        List<AgentAttachmentEntity> attachments = agentAttachmentService.findByIds(attachmentIds);
        if (attachments.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("以下是用户本轮上传的附件内容，供你参考作答（引用时注明来源文件名）：\n");
        int index = 1;
        int budget = 30000;
        for (AgentAttachmentEntity attachment : attachments) {
            if (!conversationId.equals(attachment.getConversationId())) {
                throw new BusinessException(
                        "ATTACHMENT_CONVERSATION_MISMATCH",
                        "附件不属于当前对话。attachmentId=" + attachment.getId());
            }
            sb.append("\n【附件 ").append(index++).append("】")
                    .append(attachment.getOriginalName())
                    .append("（").append(attachment.getKind()).append("）\n");
            String text = attachment.getExtractedText();
            if (AgentAttachmentEntity.KIND_IMAGE.equals(attachment.getKind())) {
                sb.append("（图片文件，暂不支持文本抽取，仅知悉其存在）\n");
            } else if (text != null && !text.isBlank()) {
                String clipped = text.length() > budget ? text.substring(0, budget) : text;
                sb.append(clipped).append('\n');
                budget -= clipped.length();
            } else {
                sb.append("（文本抽取失败或内容为空）\n");
            }
            if (budget <= 0) {
                sb.append("\n（附件内容过长，已截断）\n");
                break;
            }
        }
        return sb.toString();
    }

    private String extractText(org.springframework.ai.chat.model.ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private String resolveConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString();
        }
        return conversationId.trim();
    }

    /** Internal record carrying the outcome of one streamed turn. */
    private record StreamOutcome(Throwable error, boolean timedOut) {
    }
}
