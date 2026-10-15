package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.EvalCandidateReproduceRequest;
import com.rcdis.agent.dto.EvalCandidateReviewRequest;
import com.rcdis.agent.vo.EvalCandidateVO;
import com.rcdis.agent.vo.EvalHarvestSummaryVO;

/**
 * Discovers real Agent failures from persisted turn traces and turns them into review candidates.
 *
 * <p>The pipeline is deliberately gated: a candidate must be (1) confirmed by a person who states the
 * expected behaviour, then (2) reproduced in an isolated environment, before it can be marked as
 * promoted. Nothing here ever writes to the version-controlled dataset; {@link #exportReviewable()}
 * only produces a skeleton for a human to edit and commit.</p>
 */
public interface EvalCandidateService {

    /** Scans recent turns for failure signals and upserts deduplicated candidates. */
    EvalHarvestSummaryVO harvest(int windowDays);

    PageResponse<EvalCandidateVO> page(long current, long size, String status, String failureType);

    EvalCandidateVO get(Long id);

    /** Records a human decision; approval requires an explicit expected-behaviour statement. */
    EvalCandidateVO review(Long id, EvalCandidateReviewRequest request);

    /** Records that an approved candidate was reproduced in an isolated environment. */
    EvalCandidateVO markReproduced(Long id, EvalCandidateReproduceRequest request);

    /** Marks a reproduced candidate as merged into the dataset under the given case id. */
    EvalCandidateVO promote(Long id, String promotedCaseId, String note);

    /** JSONL skeleton of approved/reproduced candidates, for a human to finish and commit. */
    String exportReviewable();
}
