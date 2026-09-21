package com.rcdis.agent.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the Agent system prompt from a classpath resource at startup.
 *
 * <p>The prompt lives outside Java code (see {@code resources/prompts/agent-system.st}) so it can
 * be tuned without touching business logic and reviewed as a plain text artifact. It is read once
 * and cached; a restart is required to pick up edits, which is intentional for auditability.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptLoader {

    private final ResourceLoader resourceLoader;
    private final AgentProperties agentProperties;

    private String systemPrompt;

    @PostConstruct
    void loadPrompt() {
        String location = agentProperties.getSystemPromptLocation();
        if (!StringUtils.hasText(location)) {
            throw new BusinessException(
                    "AGENT_SYSTEM_PROMPT_LOCATION_MISSING",
                    "rcdis.agent.system-prompt-location 未配置，无法加载系统提示词");
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new BusinessException(
                    "AGENT_SYSTEM_PROMPT_NOT_FOUND",
                    "系统提示词资源不存在：" + location);
        }
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(
                        "AGENT_SYSTEM_PROMPT_EMPTY",
                        "系统提示词内容为空：" + location);
            }
            this.systemPrompt = content;
            log.atInfo()
                    .addKeyValue("location", location)
                    .addKeyValue("length", content.length())
                    .log("Loaded Agent system prompt");
        } catch (IOException exception) {
            throw new BusinessException(
                    "AGENT_SYSTEM_PROMPT_LOAD_FAILED",
                    "系统提示词加载失败：" + location + "，原因：" + exception.getMessage());
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
