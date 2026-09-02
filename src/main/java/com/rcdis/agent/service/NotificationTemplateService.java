package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.dto.NotificationTemplateCreateRequest;
import com.rcdis.agent.dto.NotificationTemplateDeleteRequest;
import com.rcdis.agent.dto.NotificationTemplateUpdateRequest;
import com.rcdis.agent.vo.FeishuNotificationTemplateVO;

public interface NotificationTemplateService {

    List<FeishuNotificationTemplateVO> listTemplates();

    FeishuNotificationTemplateVO getTemplate(Long id);

    FeishuNotificationTemplateVO createTemplate(NotificationTemplateCreateRequest request);

    FeishuNotificationTemplateVO updateTemplate(Long id, NotificationTemplateUpdateRequest request);

    FeishuNotificationTemplateVO toggleTemplateStatus(Long id);

    void deleteTemplate(Long id, NotificationTemplateDeleteRequest request);
}
