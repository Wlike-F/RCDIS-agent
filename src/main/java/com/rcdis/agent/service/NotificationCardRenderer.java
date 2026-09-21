package com.rcdis.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.entity.NotificationTemplateEntity;
import com.rcdis.agent.mapper.NotificationTemplateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a stored notification template into a Feishu card payload.
 *
 * <p>Rendering walks the parsed JSON tree and replaces {@code {placeholder}} tokens inside string
 * values only. It never concatenates raw text into JSON, so a value containing quotes or braces
 * cannot break the card structure. Because the walk is structure agnostic it renders card JSON 1.0
 * and 2.0 alike, including values nested inside button {@code behaviors} and {@code confirm} blocks.</p>
 *
 * <p>Shared by outbound notifications and by the card callback handler, which must render the
 * "already decided" card that replaces an approval card in place.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCardRenderer {

    private static final String TEMPLATE_STATUS_ACTIVE = "ACTIVE";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    private static final String MISSING_VALUE = "--";

    private final NotificationTemplateMapper notificationTemplateMapper;
    private final ObjectMapper objectMapper;

    /**
     * Loads the ACTIVE template and substitutes every placeholder found in its string values.
     */
    public Map<String, Object> render(String templateCode, Map<String, String> context) {
        NotificationTemplateEntity template = notificationTemplateMapper.selectOne(
                new LambdaQueryWrapper<NotificationTemplateEntity>()
                        .eq(NotificationTemplateEntity::getTemplateCode, templateCode)
                        .eq(NotificationTemplateEntity::getStatus, TEMPLATE_STATUS_ACTIVE));
        if (template == null) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_UNAVAILABLE",
                    "Notification template is missing or disabled. templateCode=" + templateCode,
                    HttpStatus.CONFLICT);
        }
        try {
            Map<String, Object> card = objectMapper.readValue(
                    template.getContent(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return replacePlaceholders(card, context, templateCode);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_CONTENT_INVALID",
                    "Notification template content is not valid JSON. templateCode=" + templateCode,
                    exception);
        }
    }

    private Map<String, Object> replacePlaceholders(
            Map<String, Object> node,
            Map<String, String> context,
            String templateCode
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            result.put(entry.getKey(), replacePlaceholderValue(entry.getValue(), context, templateCode));
        }
        return result;
    }

    private Object replacePlaceholderValue(
            Object node,
            Map<String, String> context,
            String templateCode
    ) {
        if (node instanceof String text) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
            StringBuilder builder = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                String value = context.get(key);
                if (value == null) {
                    log.atWarn()
                            .addKeyValue("templateCode", templateCode)
                            .addKeyValue("placeholder", key)
                            .log("Notification template placeholder has no context value");
                    value = MISSING_VALUE;
                }
                matcher.appendReplacement(builder, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(builder);
            return builder.toString();
        }
        if (node instanceof List<?> list) {
            return list.stream()
                    .map(item -> replacePlaceholderValue(item, context, templateCode))
                    .toList();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                        String.valueOf(entry.getKey()),
                        replacePlaceholderValue(entry.getValue(), context, templateCode));
            }
            return result;
        }
        return node;
    }
}
