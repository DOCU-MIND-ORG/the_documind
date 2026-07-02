# Advanced RAG Pipeline & Agentic Workflow

This document provides a highly detailed, comprehensive node-and-edge diagram of the DocMind RAG architecture. It maps out the exact paths a query can take, every retrieval strategy, execution tiers, conditional fallback loops, rank fusions, and memory gating steps.

## Detailed Node & Edge Architecture Flow

```mermaid
flowchart TD
    %% Styling
    classDef input fill:#f9d0c4,stroke:#333,stroke-width:2px;
    classDef api fill:#bbf,stroke:#333,stroke-width:2px;
    classDef db fill:#fdb,stroke:#333,stroke-width:2px;
    classDef worker fill:#dfd,stroke:#333,stroke-width:2px;
    classDef router fill:#ffd,stroke:#333,stroke-width:2px;
    classDef process fill:#eee,stroke:#333,stroke-width:1px;
    classDef final fill:#d4f1f4,stroke:#333,stroke-width:2px;

    %% Entry Point
    User(("User Query")):::input
    ChatAPI["ChatService"]:::api
    RedisJobs{{"Redis Stream:<br/>'chat_jobs'"}}:::db
    LlmWorker["LlmWorkerService"]:::worker
    ContextBuilder["ContextBuilderService"]:::worker
    Planner["PlannerService"]:::worker

    User -->|1. submitMessage| ChatAPI
    ChatAPI -->|2. Push Job| RedisJobs
    RedisJobs -->|3. Poll & Lock| LlmWorker
    LlmWorker -->|4. buildContext| ContextBuilder
    ContextBuilder -->|5. routeQuery| Planner

    %% Phase 1: Fast Intent Routing
    FastIntent{"FastIntentService<br/>(Regex/Rule Check)"}:::router
    Planner --> FastIntent
    
    DirectFast["Bypass Retrieval<br/>(DirectExecutionPlan)"]:::process
    FastIntent -- "GREETING_ACK" --> DirectFast
    FastIntent -- "BOT_QA" --> DirectFast
    FastIntent -- "SESSION_INFO" --> DirectFast
    FastIntent -- "META_HISTORY" --> DirectFast

    %% Phase 2: Unified LLM Routing
    LLMRouter{"Unified LLM Router<br/>(Gemini 3.1 Flash Lite)"}:::router
    FastIntent -- "No Match (Complex Query)" --> LLMRouter

    subgraph Router Classification
        Strat_SINGLE["Strategy: SINGLE_SOURCE"]:::process
        Strat_META["Strategy: META_DOC_SEARCH"]:::process
        Strat_CONCEPT["Strategy: CONCEPT_EXPANSION"]:::process
        
        Tier_DIRECT["Tier: DIRECT<br/>(1 Plan)"]:::process
        Tier_DECOMPOSE["Tier: DECOMPOSE<br/>(Multiple Plans)"]:::process
        Tier_ADAPTIVE["Tier: ADAPTIVE<br/>(Multi-Hop Search)"]:::process
        
        Mode_RANKED["Mode: RANKED_RETRIEVAL"]:::process
        Mode_WHOLE["Mode: WHOLE_DOCUMENT"]:::process
    end
    
    LLMRouter -.-> Strat_SINGLE & Strat_META & Strat_CONCEPT
    LLMRouter -.-> Tier_DIRECT & Tier_DECOMPOSE & Tier_ADAPTIVE
    LLMRouter -.-> Mode_RANKED & Mode_WHOLE
    
    LLMRouter -->|"Outputs ExecutionPlan"| ExecPlanSwitch{"Execution Plan Type"}:::router

    DirectFast --> ExecPlanSwitch
    ExecPlanSwitch -- "StaticExecutionPlan<br/>(Direct/Decompose)" --> Orchestrator["RetrievalOrchestrator"]:::worker
    
    %% Phase 3: Adaptive Retrieval Loop
    ExecPlanSwitch -- "AdaptiveExecutionPlan" --> AdaptiveLoop{"Adaptive Loop<br/>iteration <= max?"}:::router
    AdaptiveLoop -- "Yes" --> Orchestrator
    
    %% Phase 4: Hybrid Retrieval Orchestration
    Orchestrator --> ModeSwitch{"Retrieval Mode"}:::router
    
    ModeSwitch -- "WHOLE_DOCUMENT<br/>& target docs exist" --> WholeDocRet["Fetch all chunks of document<br/>(Cap at 60 chunks)"]:::process
    WholeDocRet --> RankAssembly
    
    ModeSwitch -- "RANKED_RETRIEVAL" --> HybridRet["HybridRetrievalService"]:::worker
    
    HybridRet --> ParallelSearch{"Parallel Search"}:::router
    ParallelSearch --> Pinecone[("Pinecone Vector DB<br/>Dense Search")]:::db
    ParallelSearch --> Postgres[("Postgres DB<br/>BM25 Keyword Search")]:::db
    
    Pinecone -->|"Top 15 dense"| RRF["Reciprocal Rank Fusion<br/>RRF_K=60"]:::process
    Postgres -->|"Top 15 keywords"| RRF
    
    RRF --> Diversity["Ensure Source Diversity<br/>Max 2 chunks per source"]:::process
    
    %% Phase 5: Reranking & Quality Check
    Diversity --> CrossRerank["RerankService<br/>Cross-Encoder Scoring"]:::worker
    CrossRerank --> MultiSignal["MultiSignalRanker<br/>Boost Current Session Docs"]:::worker
    MultiSignal --> RankAssembly["Aggregated Retrieval Candidates"]:::process
    
    RankAssembly --> QualityCheck{"Quality Fallback Check<br/>(Confidence Threshold)"}:::router
    QualityCheck -- "Confidence < Threshold<br/>& Target is Specific" --> ScopeExpand["Expand Scope<br/>TARGET_DOCS -> GLOBAL"]:::process
    ScopeExpand --> Orchestrator
    
    %% Returning to Adaptive Loop if needed
    QualityCheck -- "Confidence >= Threshold" --> RetResult["Retrieval Result"]:::process
    RetResult --> AdaptiveCheck{"Is Adaptive?"}:::router
    AdaptiveCheck -- "Yes" --> RetController["RetrievalController<br/>Analyze Gaps"]:::worker
    RetController -->|"Generate Next Hop Plan"| AdaptiveLoop
    AdaptiveCheck -- "No" --> MergeSwitch{"Merge Operation"}:::router
    AdaptiveLoop -- "Max Iterations Reached<br/>or STOP action" --> MergeSwitch
    
    %% Phase 6: Prompt Assembly
    MergeSwitch -- "UNION<br/>(Combine all evidence)" --> PromptBuilder["ContextBuilderService<br/>Assemble Context Block"]:::worker
    MergeSwitch -- "COMPARE<br/>(Group evidence by source/entity)" --> PromptBuilder
    MergeSwitch -- "NONE" --> PromptBuilder
    
    PromptBuilder --> FinalPrompt["Inject System Directives<br/>History & Session State"]:::process
    
    %% Phase 7: Generation & Streaming
    FinalPrompt --> GenLLM(("Generation LLM<br/>Google Gen AI")):::api
    GenLLM -->|"Stream Tokens"| RedisTokens{{"Redis Channel:<br/>'chat-tokens:id'"}}:::db
    RedisTokens -->|"Server Sent Events (SSE)"| User
    
    %% Phase 8: Post-Processing & Memory
    GenLLM -->|"Full Response Output"| DBUpdate["Update Message Status<br/>Save Citations & Visuals"]:::process
    GenLLM --> MemGating{"MemoryGatingService<br/>Is Memorable?"}:::router
    
    MemGating -- "Yes (Score > 0.4)" --> PineconeMem[("Pinecone<br/>Long-Term Memory")]:::db
    MemGating -- "No" --> End(("End Request")):::final
    PineconeMem --> End
```

## Advanced Pipeline Breakdown

### 1. Ingestion & Fast Routing
- The user query is picked up asynchronously from a Redis Stream. 
- The **FastIntentService** acts as a cheap regex-based guardrail. If the user just says "Hi" or asks "What can you do?", it bypasses the LLM router and vector search entirely to save compute, immediately emitting a `DirectExecutionPlan`.

### 2. Unified LLM Routing (Gemini 3.1 Flash Lite)
For complex queries, a small, fast LLM acts as the router to output a structured JSON plan:
- **Strategy Classifications:**
  - `SINGLE_SOURCE`: Standard factual lookup.
  - `META_DOC_SEARCH`: Searching *about* documents (e.g., "Which files discuss X?").
  - `CONCEPT_EXPANSION`: Expanding abstract concepts (e.g., "resilience") into multiple concrete keyword searches.
- **Execution Tiers:**
  - `DIRECT`: Resolves in one pass.
  - `DECOMPOSE`: Multiple queries run in parallel (e.g., comparing two distinct entities).
  - `ADAPTIVE`: A goal-oriented loop where the agent assesses findings and launches secondary queries if data is missing.
- **Retrieval Modes:**
  - `RANKED_RETRIEVAL`: The standard approach.
  - `WHOLE_DOCUMENT`: Used if the user explicitly wants to review an entire specific file.

### 3. Hybrid Retrieval & Orchestration
If not running a `WHOLE_DOCUMENT` fetch, the orchestrator triggers the `HybridRetrievalService`:
- It performs a **parallel search** against Pinecone (Dense Embeddings) and Postgres (BM25 Keywords).
- **Reciprocal Rank Fusion (RRF):** Fuses both lists (top 15 each) by calculating `1 / (RRF_K + rank)`. This surfaces results found by both engines to the top without being skewed by incompatible scoring scales.
- **Source Diversity:** A hard constraint algorithm guarantees no single document floods the results by capping it at 2 chunks per source in the initial pool.

### 4. Reranking & Quality Fallbacks
- The diverse candidate pool passes through a **Cross-Encoder** (`RerankService`) to calculate exact semantic relevance.
- The `MultiSignalRanker` applies a soft multiplier to chunks that originated in the *current session*, prioritizing actively discussed files without hard-filtering company-wide knowledge.
- **Scope Expansion (Agentic Fallback):** If the top candidate score falls below `RetrievalProperties.getPrimaryThreshold()`, and the query was artificially restricted to a specific document, the Orchestrator automatically rewrites the plan to search the `GLOBAL_CORPUS` and retries retrieval.

### 5. Adaptive Loop (Agentic Flow)
If the tier is `ADAPTIVE`, the `RetrievalController` inspects the initial results:
- If evidence is missing or partial, it reasons about the gap and generates a *new* target query to fetch missing information.
- This loop continues until it reaches an `AdaptiveActionType.STOP` state or hits the maximum allowed iterations.

### 6. Assembly & Context Merging
Based on the `MergeOperation` defined by the router (`UNION` or `COMPARE`), the chunks are stringified into a citation-aware XML-like block. The final prompt combines:
- Active File/Session metadata.
- Up to 10 previous conversation turns.
- System instructions tailored to the chosen retrieval strategy (e.g., instructing the LLM to deduce implicit themes for `CONCEPT_EXPANSION`).

### 7. Streaming & Memory Injection
Tokens are streamed real-time back to the user over Redis Pub/Sub -> SSE. Finally, the interaction is passed to the `MemoryGatingService`. If the generated answer is substantive (and retrieved context was highly relevant), the `User-Assistant` conversational pair is stored into a dedicated Pinecone index for long-term user profile memory.
