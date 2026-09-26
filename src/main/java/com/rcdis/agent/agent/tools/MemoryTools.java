package com.rcdis.agent.agent.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.service.ChatHistoryService;

import lombok.RequiredArgsConstructor;

/**
 * Memory recall tool: lets the model pull original turn text back out of the (never-deleted)
 * history when the compressed summary lacks a needed detail. This is the "lossless" safety valve.
 *
 * <p>The recalled range includes the internal {@code role=tool} rows, so the model can re-read a
 * verified tool return value rather than only the assistant's paraphrase of it.</p>
 */
@Component
@RequiredArgsConstructor
public class MemoryTools {

    private static final String TOOL_RECALL = "recall_history";

    private final ChatHistoryService chatHistoryService;
    private final AgentProperties agentProperties;

    @Tool(name = TOOL_RECALL,
            description = "回源核对：拉取本会话指定轮次区间 [fromSeq, toSeq] 的原始对话文本（含工具真实返回值）。"
                    + "当压缩摘要缺少你需要的精确数字/原文细节时使用。"
                    + "返回每行形如 [seq n] role: 原文，role 为 user/assistant/tool。可拉取的轮数与总字符数均有上限。")
    public String recallHistory(
            @ToolParam(description = "起始轮次序号(含)") Integer fromSeq,
            @ToolParam(description = "结束轮次序号(含)") Integer toSeq,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        String conversationId = ctx == null ? null : ctx.conversationId();
        if (conversationId == null || fromSeq == null || toSeq == null || toSeq < fromSeq) {
            return "{\"ok\":false,\"error\":\"参数非法：需要 fromSeq <= toSeq 且处于有效会话中\"}";
        }
        AgentProperties.Memory mem = agentProperties.getMemory();
        int from = Math.max(1, fromSeq);
        int to = Math.min(toSeq, from + mem.getRecallMaxTurns() - 1);
        List<ChatMessageEntity> turns = chatHistoryService.readTurnsWithToolResults(conversationId, from, to);
        if (turns.isEmpty()) {
            return "{\"ok\":true,\"turns\":[]}";
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        int included = 0;
        for (ChatMessageEntity row : turns) {
            String line = "[seq " + row.getSeq() + "] " + row.getRole() + ": "
                    + (row.getContent() == null ? "" : row.getContent()) + "\n";
            if (chars + line.length() > mem.getRecallMaxChars()) {
                break;
            }
            sb.append(line);
            chars += line.length();
            included++;
        }
        return "{\"ok\":true,\"fromSeq\":" + from + ",\"toSeq\":" + (from + included - 1)
                + ",\"text\":\"" + escape(sb.toString()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
