package com.rcdis.agent.vo;

/**
 * Outcome of one candidate-harvesting pass over recent turn traces.
 *
 * @param windowDays     how far back the scan looked
 * @param scanned        traces inspected that carried a failure signal
 * @param created        new candidates inserted
 * @param merged         existing candidates whose occurrence count was incremented
 * @param skippedNoInput failure turns dropped because no user utterance could be attributed
 */
public record EvalHarvestSummaryVO(
        int windowDays,
        int scanned,
        int created,
        int merged,
        int skippedNoInput
) {
}
