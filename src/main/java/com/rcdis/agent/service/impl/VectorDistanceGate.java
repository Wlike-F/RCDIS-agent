package com.rcdis.agent.service.impl;

/**
 * Cosine-distance gate for the pgvector lane of semantic-memory retrieval.
 *
 * <p>Without a threshold the vector lane always returns its {@code LIMIT} nearest neighbours, so a
 * user who merely <i>has</i> memories gets a full top-K injected every turn regardless of relevance.
 * The noise dilutes the prompt and can actively mislead the model. This gate drops neighbours that
 * are not actually close.</p>
 *
 * <p>pgvector's {@code <=>} operator returns cosine distance in {@code [0, 2]} (0 = identical
 * direction, 2 = opposite), so {@link #COSINE_DISTANCE_MAX} doubles as the "accept everything"
 * sentinel: a non-positive configuration maps to it instead of requiring dynamic SQL, which keeps
 * the mapper statement static and its {@code <=>} operator literal (an {@code @Select} statement is
 * not XML-decoded, so escaping it would silently break the lane).</p>
 */
final class VectorDistanceGate {

    /** Upper bound of pgvector cosine distance for any two vectors; also the "gate off" sentinel. */
    static final double COSINE_DISTANCE_MAX = 2.0;

    private VectorDistanceGate() {
    }

    /**
     * Normalises the configured threshold into a value that is always safe to bind into SQL.
     *
     * @param configured the configured maximum cosine distance; {@code <= 0} disables the gate
     * @return the configured value, clamped to {@code (0, 2]}, or {@link #COSINE_DISTANCE_MAX} when
     *     the gate is disabled or the value is meaningless
     */
    static double effectiveMaxDistance(double configured) {
        if (configured <= 0.0) {
            return COSINE_DISTANCE_MAX;
        }
        return Math.min(configured, COSINE_DISTANCE_MAX);
    }

    /**
     * Decides whether a recalled row is close enough to inject.
     *
     * <p>A {@code null} distance means the vector lane never ranked this row (it came from the
     * keyword lane only), so the vector gate must not reject it — otherwise enabling a vector
     * threshold would silently discard every keyword-only hit.</p>
     *
     * @param cosineDistance pgvector cosine distance to the query, or null when not vector-ranked
     * @param maxDistance    effective threshold from {@link #effectiveMaxDistance(double)}
     * @return true when the row may be injected; the bound is exclusive (distance == max is rejected)
     */
    static boolean withinThreshold(Double cosineDistance, double maxDistance) {
        if (cosineDistance == null) {
            return true;
        }
        return cosineDistance < maxDistance;
    }
}
