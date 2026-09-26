package com.rcdis.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VectorDistanceGate}, the pgvector cosine-distance threshold that stops
 * irrelevant memories from being injected just because the user happens to have some memories.
 * Pure functions, no DB — the pgvector SQL itself only runs on PostgreSQL.
 */
class VectorDistanceGateTests {

    @Test
    void distanceStrictlyBelowThresholdPasses() {
        assertThat(VectorDistanceGate.withinThreshold(0.30, 0.45)).isTrue();
        assertThat(VectorDistanceGate.withinThreshold(0.0, 0.45)).isTrue();
        assertThat(VectorDistanceGate.withinThreshold(0.4499, 0.45)).isTrue();
    }

    @Test
    void distanceAtOrAboveThresholdIsRejected() {
        // Boundary is exclusive: a hit exactly at the threshold is not "close enough".
        assertThat(VectorDistanceGate.withinThreshold(0.45, 0.45)).isFalse();
        assertThat(VectorDistanceGate.withinThreshold(0.90, 0.45)).isFalse();
        assertThat(VectorDistanceGate.withinThreshold(1.80, 0.45)).isFalse();
    }

    @Test
    void nullDistanceIsNotRejectedBecauseTheVectorLaneNeverRankedThatRow() {
        // A keyword-only hit carries no cosine distance; the vector gate must not drop it.
        assertThat(VectorDistanceGate.withinThreshold(null, 0.45)).isTrue();
    }

    @Test
    void nonPositiveConfigurationDisablesTheGate() {
        assertThat(VectorDistanceGate.effectiveMaxDistance(0.0))
                .isEqualTo(VectorDistanceGate.COSINE_DISTANCE_MAX);
        assertThat(VectorDistanceGate.effectiveMaxDistance(-1.0))
                .isEqualTo(VectorDistanceGate.COSINE_DISTANCE_MAX);
        // With the gate off, even the farthest possible neighbour is accepted.
        assertThat(VectorDistanceGate.withinThreshold(1.99, VectorDistanceGate.effectiveMaxDistance(0.0)))
                .isTrue();
    }

    @Test
    void positiveConfigurationIsPassedThroughAndClampedToTheCosineRange() {
        assertThat(VectorDistanceGate.effectiveMaxDistance(0.45)).isEqualTo(0.45);
        // Cosine distance is bounded by 2; a larger configured value is meaningless, clamp it.
        assertThat(VectorDistanceGate.effectiveMaxDistance(9.0))
                .isEqualTo(VectorDistanceGate.COSINE_DISTANCE_MAX);
    }
}
