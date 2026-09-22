package com.rcdis.agent.service;

import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.vo.OverviewSummaryVO;

/**
 * Role-aware dashboard overview: admins get the global operations block, researchers get their
 * personal reimbursement stats and the budget headroom of projects they are responsible for.
 */
public interface OverviewService {

    /** Builds the overview payload for the given (already authenticated) user. */
    OverviewSummaryVO summary(CurrentUserTO user);
}
