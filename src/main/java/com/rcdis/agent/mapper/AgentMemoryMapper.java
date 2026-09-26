package com.rcdis.agent.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.to.MemoryRecallTO;

/**
 * Persistence for cross-session semantic facts.
 *
 * <p>Standard CRUD runs through {@link BaseMapper}. The methods below are PostgreSQL-specific
 * (pgvector + pg_trgm) and are only invoked on the semantic-retrieval path, which is gated off by
 * default and wrapped in per-lane degrade handling by the retrieval service, so an absent extension
 * surfaces as a {@code DataAccessException} that the caller turns into a keyword / newest-N
 * fallback. These statements never run under the H2 test schema.</p>
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemoryEntity> {

    /** Columns fetched by the retrieval lanes; deliberately omits the large {@code embedding}. */
    String READ_COLUMNS = "id, scope, owner_user_id, project_id, fact_type, content, "
            + "source_conversation_id, source_seq, hit_count, last_hit_at, created_by, created_at";

    /**
     * Approximate nearest neighbours by cosine distance, gated by a maximum distance.
     * {@code queryVectorLiteral} is the pgvector text form {@code [v1,v2,...]}; it is cast to
     * {@code ::vector} so the bind is typed correctly.
     *
     * <p>{@code maxDistance} is always bound (never omitted via dynamic SQL) so this statement stays
     * a plain {@code @Select}: MyBatis treats it as raw text and does NOT decode XML entities, so the
     * cosine-distance operator must be written literally as {@code <=>}, never as
     * {@code &lt;=&gt;} (which would reach PostgreSQL verbatim and fail with a syntax error,
     * silently disabling the vector lane). Callers pass
     * {@code VectorDistanceGate.effectiveMaxDistance(...)}, which maps a disabled gate to the cosine
     * maximum of 2 so "accept everything" needs no separate statement.</p>
     */
    @Select("SELECT " + READ_COLUMNS + " FROM agent_memory "
            + "WHERE owner_user_id = #{userId} AND embedding IS NOT NULL "
            + "AND embedding <=> #{queryVectorLiteral}::vector < #{maxDistance} "
            + "ORDER BY embedding <=> #{queryVectorLiteral}::vector "
            + "LIMIT #{limit}")
    List<AgentMemoryEntity> searchByVector(@Param("userId") String userId,
                                           @Param("queryVectorLiteral") String queryVectorLiteral,
                                           @Param("maxDistance") double maxDistance,
                                           @Param("limit") int limit);

    /** Keyword lane ranked by pg_trgm character-trigram similarity over the whole query. */
    @Select("SELECT " + READ_COLUMNS + " FROM agent_memory "
            + "WHERE owner_user_id = #{userId} AND similarity(content, #{query}) > 0.05 "
            + "ORDER BY similarity(content, #{query}) DESC, id DESC "
            + "LIMIT #{limit}")
    List<AgentMemoryEntity> searchByKeyword(@Param("userId") String userId,
                                            @Param("query") String query,
                                            @Param("limit") int limit);

    /** Writes a single row's embedding from its pgvector text literal, cast to {@code vector}. */
    @Update("UPDATE agent_memory SET embedding = #{vectorLiteral}::vector WHERE id = #{id}")
    int updateEmbedding(@Param("id") Long id, @Param("vectorLiteral") String vectorLiteral);

    /** Oldest facts still missing an embedding, for a bounded backfill pass. */
    @Select("SELECT " + READ_COLUMNS + " FROM agent_memory "
            + "WHERE embedding IS NULL ORDER BY id ASC LIMIT #{limit}")
    List<AgentMemoryEntity> selectMissingEmbedding(@Param("limit") int limit);

    // ---------- retrieval probe (admin diagnostic; exposes per-lane scores) ----------

    /**
     * Vector lane with the cosine distance surfaced, so the probe can show how close each hit is.
     *
     * <p>Deliberately NOT distance-filtered: the probe exists to let an operator observe the whole
     * distance distribution and pick a threshold, so filtering here would hide exactly the rows the
     * threshold is meant to exclude. Per-hit acceptance is marked in Java instead.</p>
     */
    @Select("SELECT id, fact_type, content, embedding <=> #{queryVectorLiteral}::vector AS cosine_distance "
            + "FROM agent_memory WHERE owner_user_id = #{userId} AND embedding IS NOT NULL "
            + "ORDER BY embedding <=> #{queryVectorLiteral}::vector LIMIT #{limit}")
    List<MemoryRecallTO> searchByVectorScored(@Param("userId") String userId,
                                              @Param("queryVectorLiteral") String queryVectorLiteral,
                                              @Param("limit") int limit);

    /** Keyword lane with the trigram similarity surfaced, so the probe can compare against vectors. */
    @Select("SELECT id, fact_type, content, similarity(content, #{query}) AS keyword_score "
            + "FROM agent_memory WHERE owner_user_id = #{userId} AND similarity(content, #{query}) > 0.05 "
            + "ORDER BY similarity(content, #{query}) DESC, id DESC LIMIT #{limit}")
    List<MemoryRecallTO> searchByKeywordScored(@Param("userId") String userId,
                                               @Param("query") String query,
                                               @Param("limit") int limit);
}
