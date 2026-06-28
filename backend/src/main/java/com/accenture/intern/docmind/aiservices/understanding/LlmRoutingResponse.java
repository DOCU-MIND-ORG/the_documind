package com.accenture.intern.docmind.aiservices.understanding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Unified response envelope from the single LLM Router call.
 * Combines strategy classification + universal query expansion into one payload,
 * eliminating the need for a separate preprocessing round-trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmRoutingResponse(
    String strategy,

    /**
     * Whether this query is actually about the bot itself (capabilities, identity,
     * "who/what are you") rather than about document content. The fast-path keyword
     * list in FastIntentService catches the most common phrasings of this without
     * an LLM call, but free-form rephrasings ("what all can you help with", etc.)
     * fall through to this field instead of being silently forced into DOCUMENT_QA.
     * Defaults to false when the LLM omits it (i.e. assume DOCUMENT_QA), matching
     * the prior hardcoded behavior for anything this field doesn't explicitly flag.
     */
    @JsonProperty("is_bot_qa") Boolean isBotQa,

    /**
     * Keyword-dense, vocabulary-normalized version of the user's query.
     * Abstract concepts are expanded into concrete action-oriented terms.
     * E.g. "perseverance" → "enduring hardship persisting adversity overcoming obstacles".
     * Falls back to the raw query if the LLM returns null.
     */
    @JsonProperty("optimized_query") String optimizedQuery,

    /**
     * Key entities / nouns extracted from the query for downstream source filtering.
     */
    List<EntityResolution> entities,

    /**
     * Populated only for MULTI_SOURCE: one entry per comparison target.
     */
    List<ComparisonTarget> comparisons,

    /**
     * Populated only for CONCEPT_EXPANSION: 3-4 action-oriented sub-queries.
     */
    @JsonProperty("sub_queries") List<String> subQueries,

    /**
     * Explicit filenames resolved from the user's references (e.g. "the first one", "all three").
     * These map strictly to the active document names provided in the session state.
     */
    @JsonProperty("target_documents") List<String> targetDocuments,
    
    /**
     * The execution tier to route this query to:
     * DIRECT (one-shot), DECOMPOSE (static planning), or ADAPTIVE (iterative reasoning)
     */
    @JsonProperty("execution_tier") String executionTier
) {}

