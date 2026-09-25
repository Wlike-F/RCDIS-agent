package com.rcdis.agent.service.impl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RrfFuser}'s reciprocal-rank fusion: rank-based ordering, alpha weighting,
 * tie-breaking, and top-K truncation. Pure functions, no DB.
 */
class RrfFuserTests {

    @Test
    void itemRankedByBothLanesBeatsSingleLane() {
        List<Long> vector = List.of(10L, 20L, 30L);
        List<Long> keyword = List.of(20L, 40L);

        List<Long> fused = RrfFuser.fuse(vector, keyword, 0.5, 5);

        // id 20 appears near the top of both lanes, so it should lead the fusion.
        assertThat(fused.get(0)).isEqualTo(20L);
        assertThat(fused).containsExactlyInAnyOrder(10L, 20L, 30L, 40L);
    }

    @Test
    void alphaControlsLaneDominance() {
        List<Long> vector = List.of(1L);
        List<Long> keyword = List.of(2L);

        // With alpha=1 the vector lane alone contributes, so only id 1 is ranked within the top-1.
        assertThat(RrfFuser.fuse(vector, keyword, 1.0, 1)).containsExactly(1L);
        // With alpha=0 the keyword lane alone dominates within the top-1.
        assertThat(RrfFuser.fuse(vector, keyword, 0.0, 1)).containsExactly(2L);
    }

    @Test
    void topKTruncatesAndEmptyLanesAreHandled() {
        List<Long> vector = List.of(5L, 6L, 7L);

        assertThat(RrfFuser.fuse(vector, List.of(), 0.6, 2)).containsExactly(5L, 6L);
        assertThat(RrfFuser.fuse(List.of(), List.of(), 0.6, 5)).isEmpty();
        assertThat(RrfFuser.fuse(vector, vector, 0.6, 0)).isEmpty();
    }

    @Test
    void tiesAreBrokenByAscendingId() {
        // 8 (keyword rank0) and 9 (vector rank0) tie on score with alpha=0.5, so the lower id wins.
        List<Long> fused = RrfFuser.fuse(List.of(9L), List.of(8L), 0.5, 2);

        assertThat(fused).containsExactly(8L, 9L);
    }
}
