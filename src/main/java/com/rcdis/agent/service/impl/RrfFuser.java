package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion for the semantic-memory hybrid retrieval lanes.
 *
 * <p>Vector (cosine nearest neighbours) and keyword (trigram similarity) lanes have incomparable
 * score scales, so fusion is rank-based rather than score-based: each item earns
 * {@code weight / (k + rank)} from each lane it appears in, with {@code k = 60} the conventional RRF
 * smoothing constant. The vector lane weight is {@code alpha} and the keyword lane takes
 * {@code 1 - alpha}, letting operators tilt toward semantics or literal match via configuration.</p>
 */
final class RrfFuser {

    private static final int K = 60;

    private RrfFuser() {
    }

    /**
     * Fuses two relevance-ranked id lists into a single ordered list.
     *
     * @param vectorRankedIds  ids ordered most-to-least relevant by the vector lane (may be empty)
     * @param keywordRankedIds ids ordered most-to-least relevant by the keyword lane (may be empty)
     * @param alpha            weight of the vector lane in {@code [0,1]}; keyword gets {@code 1-alpha}
     * @param topK             maximum ids to return; non-positive returns an empty list
     * @return fused ids ordered by descending combined RRF score, ties broken by ascending id
     */
    static List<Long> fuse(List<Long> vectorRankedIds,
                           List<Long> keywordRankedIds,
                           double alpha,
                           int topK) {
        if (topK <= 0) {
            return List.of();
        }
        double vectorWeight = clamp01(alpha);
        double keywordWeight = 1.0 - vectorWeight;

        Map<Long, Double> scores = new HashMap<>();
        accumulate(scores, vectorRankedIds, vectorWeight);
        accumulate(scores, keywordRankedIds, keywordWeight);

        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : Long.compare(a.getKey(), b.getKey());
        });

        List<Long> fused = new ArrayList<>(Math.min(topK, ranked.size()));
        for (int i = 0; i < ranked.size() && i < topK; i++) {
            fused.add(ranked.get(i).getKey());
        }
        return fused;
    }

    private static void accumulate(Map<Long, Double> scores, List<Long> rankedIds, double weight) {
        if (rankedIds == null || weight == 0.0) {
            return;
        }
        for (int rank = 0; rank < rankedIds.size(); rank++) {
            Long id = rankedIds.get(rank);
            if (id == null) {
                continue;
            }
            scores.merge(id, weight / (K + rank + 1), Double::sum);
        }
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
