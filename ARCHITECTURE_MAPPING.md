# Architecture Code Mapping

This document maps every stage and component defined in the `DREAMED_ARCHITECTURE.md` flowchart to its corresponding implementation file in the DocMind backend codebase. It also outlines the additional responsibilities covered by each file.

## 1. Entry Point & Job Orchestration

### `ChatService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/chat/ChatService.java`
- **Flowchart Role:** `ChatAPI`
- **What Else it Covers:** Exposes the REST controller endpoints for chat. Validates the user session, performs basic rate-limiting or validation, and asynchronously pushes the query payload to the `chat_jobs` Redis stream.

### `LlmWorkerService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/chat/LlmWorkerService.java`
- **Flowchart Role:** `LlmWorker`, Generation LLM Execution, Redis SSE Streaming
- **What Else it Covers:** Acts as a continuous background consumer polling the `chat_jobs` stream. It coordinates the entire pipeline (triggering Topic Shift Detection, Context Building, and final LLM Generation). It also streams tokens back via Redis Pub/Sub, extracts citations and visuals from the final result, updates the Postgres `Message` status, triggers follow-up question generation, and manages chat analytics/telemetry.

---

## 2. Phase 1: Fast Intent Routing

### `FastIntentService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/fastintent/FastIntentService.java`
- **Flowchart Role:** `FastIntentService` (Regex/Rule Check)
- **What Else it Covers:** Provides a high-speed, zero-cost classification layer using regex and keyword sets. It intercepts basic queries (greetings, asking for session info, "who are you?", meta history) and outputs a `DirectExecutionPlan`, saving the cost of a full LLM router call.

---

## 3. Phase 2: Unified LLM Routing

### `PlannerService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/understanding/PlannerService.java`
- **Flowchart Role:** `PlannerService`, `Unified LLM Router` (Gemini 3.1 Flash Lite)
- **What Else it Covers:** Prompts a small, fast LLM to parse complex user intents into structured JSON (`LlmRoutingResponse`). It defines the system prompt dictating the `RetrievalStrategy` (Concept Expansion, Meta Doc Search, Memory intents) and the `ExecutionTier` (Direct, Decompose, Adaptive). It also determines if a `visual_search` is necessary and builds the resulting `ExecutionPlan` object.

---

## 4. Phase 3 & 4: Retrieval Orchestration (Text + Visual)

### `RetrievalOrchestrator.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/retrieval/RetrievalOrchestrator.java`
- **Flowchart Role:** `RetrievalOrchestrator`
- **What Else it Covers:** The "brain" of the retrieval process. It executes the plan created by the Planner. It loops over `AdaptiveExecutionPlan`s using the `RetrievalController`, merges multiple parallel retrieval results (UNION/COMPARE), manages the Quality Fallback Check (expanding scope if confidence is too low), and delegates to the `HybridRetrievalService`.

### `HybridRetrievalService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/retrieval/HybridRetrievalService.java`
- **Flowchart Role:** `HybridRetrievalService` (Text and Visual Branches), Reciprocal Rank Fusion (RRF), Source Diversity
- **What Else it Covers:** Manages the actual database lookups. It performs parallel searches against Pinecone (Dense) and Postgres (BM25 Keyword). It implements Reciprocal Rank Fusion to combine diverse scoring scales. It enforces source diversity limits (max chunks per source). It also supports an `imageOnly` mode to query exclusively for visual evidence (diagrams, photos).

---

## 5. Phase 5: Reranking & Quality Check

### `RerankService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/retrieval/RerankService.java`
- **Flowchart Role:** `RerankService` (Cross-Encoder Scoring)
- **What Else it Covers:** Interfaces with an external Cross-Encoder API (like Cohere) to take the top N diverse candidates from the Hybrid Retrieval and precisely rescore them based on semantic relevance to the query. 

### `MultiSignalRanker.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/retrieval/MultiSignalRanker.java`
- **Flowchart Role:** `MultiSignalRanker`
- **What Else it Covers:** Applies post-rerank heuristics. It applies a soft multiplier to boost the scores of chunks that originate from documents actively uploaded/referenced in the user's *current* session context, prioritizing current context over global knowledge without hard-filtering.

### `RetrievalController.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/understanding/plan/RetrievalController.java`
- **Flowchart Role:** `RetrievalController` (Analyze Gaps)
- **What Else it Covers:** The agentic loop evaluator used during `ADAPTIVE` execution. It uses an LLM to read the retrieved chunks and reason about whether the user's goal has been met. If information is missing, it outputs a new generated query (hop) to fetch the missing data.

---

## 6. Phase 6: Prompt Assembly

### `ContextBuilderService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/context/ContextBuilderService.java`
- **Flowchart Role:** `ContextBuilderService`
- **What Else it Covers:** Orchestrates the transition from query to final prompt. It calls the Planner, calls the Orchestrator, and finally merges all retrieved text chunks, visual evidence, active file metadata, and conversation history into a massive, citation-aware XML-like context block injected with system directives.

---

## 7. Phase 8: Post-Processing & Dual-Layer Memory

### `MemoryGatingService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/memory/MemoryGatingService.java`
- **Flowchart Role:** `MemoryGatingService`
- **What Else it Covers:** A fast heuristic filter that checks if the finalized user query and assistant response pair is substantive enough to save (e.g. ignores "hi", "thanks", or answers where the bot failed to find information).

### `TopicShiftDetectorService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/memory/TopicShiftDetectorService.java`
- **Flowchart Role:** `TopicShiftDetectorService`
- **What Else it Covers:** Runs at the start of a query to detect if the user has shifted the conversation topic. Manages the ongoing "episode" state in Redis.

### `MemoryWorkerTriggerService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/aiservices/memory/MemoryWorkerTriggerService.java`
- **Flowchart Role:** `Redis Stream: 'memory_jobs'`
- **What Else it Covers:** Packages up a closed conversation episode and publishes it asynchronously to the `memory_jobs` Redis stream.

### `MemoryWorkerService.java`
- **Path:** `backend/src/main/java/com/accenture/intern/docmind/service/MemoryWorkerService.java`
- **Flowchart Role:** `MemoryWorkerService`
- **What Else it Covers:** A background daemon that consumes the `memory_jobs` stream. It uses an LLM to generate an "Episodic Summary" (including topic, importance score, and entities) and persists it simultaneously to Postgres (`ConversationTimeline`) and Pinecone (as long-term episodic memory vectors).
