package com.rcdis.agent.infrastructure.feishu;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.ws.Client;
import com.rcdis.agent.config.FeishuProperties;
import com.rcdis.agent.service.FeishuCardActionService;
import com.rcdis.agent.to.CardActionOutcomeTO;
import com.rcdis.agent.to.CardActionRequestTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Receives Feishu card callbacks over the official WebSocket long connection.
 *
 * <p>This is what makes interactive approval cards usable from a workstation: no public HTTPS
 * endpoint, no reverse tunnel and no inbound firewall rule. The channel is authenticated with the
 * app credentials during the handshake, which is why the dispatcher is built with empty verification
 * token and encrypt key arguments as the SDK documents.</p>
 *
 * <p>Enabling it requires adding the "卡片回传交互" ({@code card.action.trigger}) callback in the
 * developer console and choosing the long-connection subscription mode. Only the new callback works
 * here; the legacy {@code card.action.trigger_v1} does not support long connections, which is why the
 * approval card is written in card JSON 2.0 with {@code behaviors:[{type:"callback"}]}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
@ConditionalOnExpression("'${rcdis.feishu.callback-mode:none}' == 'ws' && '${rcdis.feishu.enabled:false}' == 'true' "
        + "&& '${rcdis.feishu.client-type:webhook}' == 'app'")
public class FeishuWsCardCallbackListener implements SmartLifecycle {

    private static final String CARD_TYPE_RAW = "raw";
    private static final long AWAIT_READY_MILLIS = 10_000L;

    private final FeishuProperties properties;
    private final FeishuCardActionService feishuCardActionService;
    private final ObjectMapper objectMapper;

    private volatile Client client;
    private volatile boolean running;

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            log.atError().log("Feishu long connection needs rcdis.feishu.app-id and app-secret; listener not started");
            return;
        }
        try {
            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                        @Override
                        public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                            return onCardAction(event);
                        }
                    })
                    .build();
            Client wsClient = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                    .eventHandler(dispatcher)
                    .autoReconnect(Boolean.TRUE)
                    .build();
            wsClient.start();
            this.client = wsClient;
            this.running = true;
            awaitReady(wsClient);
        } catch (RuntimeException | Error exception) {
            // A failed Feishu connection must not stop the rest of the application from serving HTTP.
            this.running = false;
            log.atError()
                    .setCause(exception)
                    .log("Failed to start the Feishu card callback long connection; approval cards will not be "
                            + "actionable until it is restored");
        }
    }

    private void awaitReady(Client wsClient) {
        try {
            wsClient.awaitReady(AWAIT_READY_MILLIS);
            log.atInfo().log("Feishu card callback long connection is ready");
        } catch (Exception exception) {
            log.atWarn()
                    .setCause(exception)
                    .log("Feishu card callback long connection did not report ready within {} ms; it keeps retrying",
                            AWAIT_READY_MILLIS);
        }
    }

    @Override
    public void stop() {
        Client current = this.client;
        this.running = false;
        this.client = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
            log.atInfo().log("Feishu card callback long connection closed");
        } catch (RuntimeException exception) {
            log.atWarn().setCause(exception).log("Failed to close the Feishu long connection cleanly");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Normalizes the SDK event into the transport agnostic request, delegates to the shared handler
     * and maps the outcome back onto the SDK response.
     */
    private P2CardActionTriggerResponse onCardAction(P2CardActionTrigger event) {
        CardActionRequestTO request = toRequest(event);
        log.atInfo()
                .addKeyValue("eventId", request.eventId())
                .addKeyValue("actionTag", request.actionTag())
                .log("Feishu card action received over the long connection");
        CardActionOutcomeTO outcome;
        try {
            outcome = feishuCardActionService.handleCardAction(request);
        } catch (RuntimeException exception) {
            log.atError()
                    .setCause(exception)
                    .addKeyValue("eventId", request.eventId())
                    .log("Feishu card action handling failed");
            outcome = CardActionOutcomeTO.toast(
                    CardActionOutcomeTO.TOAST_ERROR,
                    "处理失败，请到报销中心查看该单据状态后再操作");
        }
        return toResponse(outcome);
    }

    private CardActionRequestTO toRequest(P2CardActionTrigger event) {
        Header header = event == null ? null : event.getHeader();
        P2CardActionTriggerData data = event == null ? null : event.getEvent();
        CallBackOperator operator = data == null ? null : data.getOperator();
        CallBackAction action = data == null ? null : data.getAction();
        CallBackContext context = data == null ? null : data.getContext();
        return new CardActionRequestTO(
                header == null ? null : header.getEventId(),
                header == null ? null : header.getEventType(),
                header == null ? null : header.getToken(),
                operator == null ? null : operator.getOpenId(),
                operator == null ? null : operator.getUserId(),
                operator == null ? null : operator.getTenantKey(),
                context == null ? null : context.getOpenChatId(),
                context == null ? null : context.getOpenMessageId(),
                action == null ? null : action.getTag(),
                action == null ? null : action.getValue(),
                action == null ? null : action.getFormValue(),
                auditPayload(header, operator, action, context));
    }

    /**
     * Builds a compact audit payload from the fields that matter instead of serializing the whole SDK
     * event, which keeps the stored row small and free of fields this application never uses.
     */
    private String auditPayload(Header header, CallBackOperator operator, CallBackAction action,
                                CallBackContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", header == null ? null : header.getEventId());
        payload.put("eventType", header == null ? null : header.getEventType());
        payload.put("transport", "ws");
        payload.put("operatorOpenId", operator == null ? null : operator.getOpenId());
        payload.put("actionTag", action == null ? null : action.getTag());
        payload.put("actionValue", action == null ? null : action.getValue());
        payload.put("formValue", action == null ? null : action.getFormValue());
        payload.put("openChatId", context == null ? null : context.getOpenChatId());
        payload.put("openMessageId", context == null ? null : context.getOpenMessageId());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            log.atWarn().setCause(exception).log("Failed to serialize the Feishu card callback audit payload");
            return "{}";
        }
    }

    private P2CardActionTriggerResponse toResponse(CardActionOutcomeTO outcome) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        toast.setType(outcome.toastType());
        toast.setContent(outcome.toastContent());
        response.setToast(toast);
        if (outcome.card() != null) {
            CallBackCard card = new CallBackCard();
            card.setType(CARD_TYPE_RAW);
            card.setData(outcome.card());
            response.setCard(card);
        }
        return response;
    }
}
