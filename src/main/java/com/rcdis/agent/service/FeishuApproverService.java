package com.rcdis.agent.service;

import java.util.List;
import java.util.Optional;

import com.rcdis.agent.dto.FeishuApproverBindRequest;
import com.rcdis.agent.dto.FeishuApproverDeleteRequest;
import com.rcdis.agent.to.FeishuApproverTO;
import com.rcdis.agent.vo.FeishuApproverVO;
import com.rcdis.agent.vo.FeishuChatMemberVO;

/**
 * Manages the binding between Feishu users and reimbursement approvers.
 *
 * <p>This binding is the authorization source for interactive approval cards: a Feishu card cannot
 * hide its buttons per recipient, so every callback must be checked against an active binding here
 * before any financial state changes.</p>
 */
public interface FeishuApproverService {

    List<FeishuApproverVO> listApprovers();

    /**
     * Lists the approval group's members and marks those already bound.
     */
    List<FeishuChatMemberVO> listChatMembers();

    FeishuApproverVO bindApprover(FeishuApproverBindRequest request);

    FeishuApproverVO toggleApproverStatus(Long id);

    void unbindApprover(Long id, FeishuApproverDeleteRequest request);

    /**
     * Resolves an active approver by Feishu open_id.
     */
    Optional<FeishuApproverTO> findActiveByOpenId(String openId);

    /**
     * All active approvers, used to decide whom to private-message an approval card to.
     */
    List<FeishuApproverTO> listActiveApprovers();
}
