package com.rcdis.agent.infrastructure.feishu;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.to.FeishuChatMemberTO;

/**
 * Pins the Feishu chat member wire format.
 *
 * <p>{@link #LIVE_PAGE} was captured from a real tenant_access_token call against
 * {@code GET /open-apis/im/v1/chats/{chat_id}/members}. Reading the member list from
 * {@code data.members} instead of the actual {@code data.items} once produced a silently empty
 * approver picker that no test caught, so the shape is asserted here explicitly.</p>
 */
class FeishuChatMemberClientTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LIVE_PAGE = """
            {"code":0,"msg":"success","data":{"has_more":false,"member_total":1,"page_token":"",
            "security_conf_limit":0,"trigger_security_conf_limit":false,
            "items":[{"member_id":"ou_ac24e28b506b83b9250680a5b987c965","member_id_type":"open_id",
            "name":"用户236931","tenant_key":"1a5a733d41481c8d"}]}}
            """;

    @Test
    void parsesTheLiveMemberPageShape() {
        List<FeishuChatMemberTO> members = FeishuChatMemberClient.parseMembers(MAPPER, LIVE_PAGE);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).openId()).isEqualTo("ou_ac24e28b506b83b9250680a5b987c965");
        assertThat(members.get(0).name()).isEqualTo("用户236931");
        assertThat(members.get(0).tenantKey()).isEqualTo("1a5a733d41481c8d");
    }

    @Test
    void parsesSeveralMembersAndSkipsEntriesWithoutAnId() {
        String body = """
                {"code":0,"msg":"success","data":{"has_more":false,"member_total":3,"items":[
                {"member_id":"ou_a","name":"张三","tenant_key":"t"},
                {"member_id":"","name":"没有 id 的成员"},
                {"member_id":"ou_b","name":"李四","tenant_key":"t"}]}}
                """;

        List<FeishuChatMemberTO> members = FeishuChatMemberClient.parseMembers(MAPPER, body);

        assertThat(members).hasSize(2);
        assertThat(members).extracting(FeishuChatMemberTO::openId).containsExactly("ou_a", "ou_b");
        assertThat(members).extracting(FeishuChatMemberTO::name).containsExactly("张三", "李四");
    }

    @Test
    void anEmptyGroupYieldsNoMembers() {
        String body = "{\"code\":0,\"msg\":\"success\",\"data\":{\"has_more\":false,\"member_total\":0,\"items\":[]}}";

        assertThat(FeishuChatMemberClient.parseMembers(MAPPER, body)).isEmpty();
    }

    @Test
    void aMissingScopeIsSurfacedWithTheFeishuCode() {
        // 99991672 is what Feishu returns when the app lacks a chat membership read scope.
        String body = "{\"code\":99991672,\"msg\":\"Access denied. One of the following scopes is required: "
                + "[im:chat:readonly, im:chat, im:chat.group_info:readonly, im:chat.members:read]\"}";

        assertThatThrownBy(() -> FeishuChatMemberClient.parseMembers(MAPPER, body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99991672")
                .hasMessageContaining("im:chat:readonly");
    }

    @Test
    void malformedAndEmptyBodiesAreRejected() {
        // The error code lives on BusinessException, not in its message.
        BusinessException malformed = catchThrowableOfType(
                () -> FeishuChatMemberClient.parseMembers(MAPPER, "not json"), BusinessException.class);
        assertThat(malformed).isNotNull();
        assertThat(malformed.getCode()).isEqualTo("FEISHU_CHAT_MEMBERS_RESPONSE_INVALID");

        BusinessException blank = catchThrowableOfType(
                () -> FeishuChatMemberClient.parseMembers(MAPPER, "  "), BusinessException.class);
        assertThat(blank).isNotNull();
        assertThat(blank.getCode()).isEqualTo("FEISHU_CHAT_MEMBERS_EMPTY_RESPONSE");
    }
}
