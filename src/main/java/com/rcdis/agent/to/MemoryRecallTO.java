package com.rcdis.agent.to;

import lombok.Getter;
import lombok.Setter;

/**
 * A single semantic-memory recall row enriched with the lane scores that produced it.
 *
 * <p>Used only by the retrieval probe (an admin diagnostic). It is a mutable bean rather than a
 * record because MyBatis maps the aggregate/score columns ({@code cosine_distance}, {@code
 * keyword_score}) onto setters via the underscore-to-camel configuration; record auto-mapping is
 * unreliable for these projection columns.</p>
 */
@Getter
@Setter
public class MemoryRecallTO {

    private Long id;
    private String factType;
    private String content;
    /** pgvector cosine distance to the query (0 = identical); null when the vector lane skipped it. */
    private Double cosineDistance;
    /** pg_trgm similarity to the query (1 = identical); null when the keyword lane skipped it. */
    private Double keywordScore;
    /** 1-based fused rank after reciprocal-rank fusion; null when not in the final top-K. */
    private Integer fusedRank;
}
