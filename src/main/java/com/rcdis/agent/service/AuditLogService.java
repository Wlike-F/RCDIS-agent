package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.to.AuditLogEntryTO;
import com.rcdis.agent.vo.AuditLogVO;

public interface AuditLogService {

    void record(AuditLogEntryTO entry);

    PageResponse<AuditLogVO> list(long current, long size);
}
