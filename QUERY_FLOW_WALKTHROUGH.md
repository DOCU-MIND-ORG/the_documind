# DocMind — End-to-End Query Flow Walkthrough

This document traces the exact journey of a user query from the moment it enters the system to the final streamed response, for **every possible case**. At each step, we show what file handles it, what it receives, what it produces, and what the LLM outputs (if called).

---

## Step 0: User Sends a Message

**File**: `ChatService.java`
**What happens**: The user types a message in the React frontend and hits send. The frontend calls `POST /api/chat/sessions/{sessionId}/messages`. `ChatService` validates the session, creates a new `Message` entity in Postgres with status `PENDING`, and pushes a job onto the `chat_jobs` Redis Stream containing `{ messageId, sessionId, query, model }`. It immediately returns the `messageId` to the frontend, which then opens an SSE connection to listen for streamed tokens on the Redis channel `chat-tokens:{messageId}`.

**Output**: A Redis Stream record in `chat_jobs`.

---

## Step 1: Worker Picks Up the Job

**File**: `LlmWorkerService.java`
**What happens**: `LlmWorkerService` runs an infinite loop (`runWorker()`) polling the `chat_jobs` stream using Redis consumer groups. When it picks up a record, it acquires a distributed lock (`lock:{messageId}`) to prevent duplicate processing. It then enters `executeLlmGeneration()`, which is the main orchestrator method that drives the entire pipeline.

**Receives**: `{ messageId, sessionId, query, model }` from Redis.

---

## Step 2: Topic Shift Detection

**File**: `TopicShiftDetectorService.java`
**What happens**: Before building any context, `LlmWorkerService` calls `topicShiftDetectorService.detectTopicShift(sessionId, query)`. This service maintains a per-session "episode" in Redis (`session:episode:messages:{sessionId}`).

- **If this is the first message**: Initializes a new episode with this query. Returns `false` (no shift).
- **If messages exist**: Sends the current episode + new query to an LLM with the prompt: *"Determine if the user's new query represents a SIGNIFICANT topic shift. Respond with exactly TRUE or FALSE."*

**LLM Output** (example):
```
FALSE
```

- **If TRUE (topic shifted)**:
  1. The old episode messages are packaged and sent to `MemoryWorkerTriggerService.triggerMemoryJob()`.
  2. **File**: `MemoryWorkerTriggerService.java` — Serializes the episode into a `MemoryJobPayload` and pushes it to the `memory_jobs` Redis Stream.
  3. A new episode is started with the current query.
  4. (Asynchronously, `MemoryWorkerService.java` will later consume this job — see [Memory Worker](#memory-worker-async) below.)

- **If FALSE**: The query is appended to the current episode.

**Output**: `boolean topicShifted` (used only for logging; the pipeline continues regardless).

---

## Step 3: Session State Resolution

**File**: `LlmWorkerService.java` + `SessionCacheService.java`
**What happens**: The worker checks if the session has documents currently being ingested. If `UploadState` is `EMBEDDING` or `INGESTING`, it **waits** (polling every 1 second, up to 120 seconds) for ingestion to complete before proceeding. This prevents the user from asking about a document that hasn't finished indexing yet.

It then builds a `SessionContext` containing:
- The list of `DocumentReference` objects (filename + timestamp) for all uploaded documents.
- The active document name.
- The alias map (e.g., `"first" → "report.pdf"`, `"latest" → "resume.pdf"`).

**Output**: `SessionContext` object passed to the context builder.

---

## Step 4: Context Building Begins

**File**: `ContextBuilderService.java`
**What happens**: `LlmWorkerService` calls `contextBuilderService.buildContext(query, sessionId, sessionContext, stillIndexing, progressSink)`. This is the central orchestrator for the entire RAG pipeline.

### Step 4a: Fetch Conversation History

**File**: `ContextBuilderService.java` → `MessageRepository`
**What happens**: Loads the last 10 messages from Postgres for this session, reverses them into chronological order, and formats them as:
```
User: What is quantum computing?
Assistant: Quantum computing uses qubits...
User: How does it compare to classical computing?
```

**Output**: `historyBlock` (String).

### Step 4b: Route the Query

**File**: `ContextBuilderService.java` → `PlannerService.java`
**What happens**: Calls `plannerService.routeQuery(query, historyBlock, sessionContext, progressSink)`.

---

## Step 5: Fast Intent Check

**File**: `PlannerService.java` → `FastIntentService.java`
**What happens**: `PlannerService` first calls `fastIntentService.classifyIntent(cleanQuery)`. This does pure regex/keyword matching.

---

### CASE A: Fast Intent Matches (Greeting / BotQA / SessionInfo / MetaHistory)

**Example query**: `"hello"` → `Intent.GREETING_ACK`

**What happens**: `PlannerService` immediately returns a `DirectExecutionPlan` with `Scope.NONE`. No LLM Router call is made.

**Back in `ContextBuilderService`**:
- For `GREETING_ACK` or `BOT_QA`: Returns `ContextResult` with `generalPrompt` as the system prompt and no retrieval context. The LLM gets a clean prompt with just the user's greeting + conversation history.
- For `SESSION_INFO`: Synthesizes a context block from `sessionContext.uploadedDocuments()` listing all uploaded files. No vector search.
- For `META_HISTORY`: Sets the context to "The user is asking about past conversations. Rely on the Conversation History above."

**→ Jumps to [Step 10: Generation](#step-10-generation).**

---

### CASE B: No Fast Intent Match → LLM Router Fires

**Example query**: `"What does the report say about climate change?"`

**File**: `PlannerService.java` → `callUnifiedRouter()`
**What happens**: Builds a massive prompt containing:
- The list of uploaded documents and their aliases.
- Strategy classification instructions (SINGLE_SOURCE / META_DOC_SEARCH / CONCEPT_EXPANSION / TIMELINE_QUERY / EPISODIC_SUMMARY / SPECIFIC_TOPIC).
- Execution tier instructions (DIRECT / DECOMPOSE / ADAPTIVE).
- Retrieval mode instructions (RANKED / WHOLE_DOCUMENT).
- Visual search instructions.
- Entity extraction instructions.
- The conversation history.
- The user's query.

Sends this to **Gemini 3.1 Flash Lite** (temperature=0.0).

**LLM Output** (example for a simple factual query):
```json
{
  "is_bot_qa": false,
  "strategy": "SINGLE_SOURCE",
  "execution_tier": "DIRECT",
  "retrieval_mode": "RANKED",
  "merge_operation": "NONE",
  "visual_search": false,
  "entities": [
    { "name": "climate change", "confidence": 0.95 }
  ],
  "plans": [
    {
      "purpose": "Climate change analysis from report",
      "optimized_query": "climate change global warming environmental impact greenhouse gas emissions",
      "target_documents": ["environmental_report.pdf"]
    }
  ]
}
```

**File**: `PlannerService.java`
**What happens**: Parses this JSON into an `LlmRoutingResponse` record. Based on the `execution_tier`:
- `"DIRECT"` → Creates a `DirectExecutionPlan` with 1 `RetrievalPlan`.
- `"DECOMPOSE"` → Creates a `StaticExecutionPlan` with N `RetrievalPlan`s.
- `"ADAPTIVE"` → Creates an `AdaptiveExecutionPlan` with the initial plan + `maxIterations=2`.

Each `RetrievalPlan` contains: `purpose`, `optimizedQuery` (the LLM-rewritten keyword-dense query), `targetDocuments`, `executionMode`, and `scope`.

---

## Step 6: Memory Intent Routing (if applicable)

**File**: `ContextBuilderService.java`
**What happens**: Before triggering retrieval, `ContextBuilderService` checks the plan's `purpose` field:

### CASE B1: `TIMELINE_QUERY`

**Example query**: `"What did we discuss today?"`

**File**: `ContextBuilderService.java` → `ConversationTimelineRepository`
**What happens**: Fetches all `ConversationTimeline` records for this session from Postgres, formats them as:
```
CONVERSATION TIMELINE:
Turns 1-5 | Topic: Black Holes | Summary: Discussed formation and types... | Entities: [Sagittarius A*, event horizon]

Turns 6-12 | Topic: SpaceX Launches | Summary: Covered Falcon 9 reusability... | Entities: [Falcon 9, Starship]
```

**→ Jumps to [Step 10: Generation](#step-10-generation)** with this timeline as context. No vector search.

### CASE B2: `EPISODIC_SUMMARY`

**Example query**: `"Remind me about our past discussion on SpaceX"`

Same as TIMELINE_QUERY but formats the output differently (topic + summary only, no turn numbers).

**→ Jumps to [Step 10: Generation](#step-10-generation).**

### CASE B3: `SPECIFIC_TOPIC`

**Example query**: `"What did you say the distance to the moon was?"`

**File**: `ContextBuilderService.java` → `MessagesPineconeVectorStore`
**What happens**: Runs a similarity search against the Pinecone index storing past User-Assistant pairs, filtered by `sessionId`. Returns the top 5 matching past conversations.

**→ Jumps to [Step 10: Generation](#step-10-generation)** with these past messages as context.

---

### CASE B4: Standard Document Query (SINGLE_SOURCE, META_DOC_SEARCH, CONCEPT_EXPANSION)

**→ Continues to [Step 7: Retrieval Orchestration](#step-7-retrieval-orchestration).**

---

## Step 7: Retrieval Orchestration

### CASE B4a: WHOLE_DOCUMENT Mode (Specific Doc + Deictic Reference)

**Example query**: `"Summarize this document"` (with an uploaded PDF)

**File**: `ContextBuilderService.java` → `HybridRetrievalService.wholeDocumentRetrieve()`
**What happens**: If the scope is `SPECIFIC_DOC` and the execution mode is `WHOLE_DOCUMENT`, `ContextBuilderService` bypasses the orchestrator entirely. It calls `hybridRetrievalService.wholeDocumentRetrieve(docName)` for each target document, which:
1. Normalizes the filename (strip extension, lowercase, replace underscores with spaces).
2. Fetches ALL chunks from Postgres `document_chunks` table, ordered by `chunkIndex`.
3. Caps at 60 chunks to prevent context flooding.
4. Returns them all with score 1.0.

**Output**: List of `RetrievalCandidate` objects (all chunks of the document, in order).

**→ Jumps to [Step 9: Evidence Structuring](#step-9-evidence-structuring).**

---

### CASE B4b: RANKED_RETRIEVAL Mode (Standard Path)

**File**: `ContextBuilderService.java` → `RetrievalOrchestrator.java`
**What happens**: Calls `retrievalOrchestrator.orchestrate(question, sessionId, execPlan, progressSink)`.

The orchestrator iterates over each `RetrievalPlan` in the execution plan (1 for DIRECT, N for DECOMPOSE) and for each plan:

---

## Step 7.1: Text Retrieval (Per Plan)

**File**: `RetrievalOrchestrator.java` → `HybridRetrievalService.java`

### 7.1a: Parallel Dense + Keyword Search

**File**: `HybridRetrievalService.rankedRetrieve()`

Two searches run **simultaneously** via `Mono.zip()`:

1. **Dense Search (Pinecone)**:
   - **File**: `VectorStoreService.java` → `IntegratedPineconeVectorStore.java`
   - Sends the `optimizedQuery` (the LLM-rewritten keyword-dense query) to Google's embedding model.
   - Queries Pinecone for the top 15 nearest vectors.
   - If `targetDocuments` are specified, adds a metadata filter.
   - If `imageOnly=true`, filters to only `isImage=true` vectors.
   - Returns `List<Document>` with metadata intact.

2. **Keyword Search (Postgres BM25)**:
   - **File**: `DocumentChunkRepository.java` (native SQL query)
   - Runs `plainto_tsquery()` + `ts_rank()` over the `document_chunks` table.
   - Returns top 15 keyword matches.
   - **Skipped** if the query has fewer than 3 tokens.
   - If `imageOnly=true`, only returns chunks with `image_url IS NOT NULL`.

### 7.1b: Reciprocal Rank Fusion (RRF)

**File**: `HybridRetrievalService.fuse()`
**What happens**: Takes the two ranked lists (dense + keyword) and fuses them:
```
For each document in each list:
    rrfScore[doc] += 1 / (60 + rank + 1)
```
Documents found by **both** retrievers accumulate scores from both lists and rise to the top. Each document is also tagged with its `retrievalPaths` (e.g., `["dense", "bm25"]`).

### 7.1c: Source Diversity

**File**: `HybridRetrievalService.ensureSourceDiversity()`
**What happens**: Enforces max 2 chunks per unique `sourceName` in the initial pool, then fills remaining slots up to 15 with the highest-ranked remaining chunks.

**Output**: `List<RetrievalCandidate>` — 15 diverse, RRF-ranked candidates.

---

## Step 7.2: Visual Retrieval (Parallel, if `isVisualSearch=true`)

**File**: `RetrievalOrchestrator.java`
**What happens**: Runs **in parallel** with text retrieval via `Mono.zip()`:

1. Calls `HybridRetrievalService.retrieve(..., imageOnly=true)` — same hybrid pipeline but filtered to image chunks only.
2. Reranks the image results: `rerankService.rerank(question, docs, topK=3)`.
3. Applies multi-signal ranking: `multiSignalRanker.rank(docs, plan, entities, sessionId)`.
4. Extracts top 3 as `VisualEvidence` objects:
   ```
   VisualEvidence {
     semanticId: "wiki_img_3",
     imageUrl: "https://upload.wikimedia.org/...",
     caption: "[Image Type: DIAGRAM]\n[Summary: A flowchart showing...]...",
     score: 0.87,
     sourceDocument: "quantum computing"
   }
   ```

**Output**: `List<VisualEvidence>` (max 3 images).

---

## Step 8: Reranking & Quality Check

### 8.1: Cross-Encoder Reranking

**File**: `RerankService.java`
**What happens**: Takes the 15 RRF-fused candidates and sends each `(query, chunk_text)` pair to a cross-encoder API. The cross-encoder sees both texts together and outputs a precise relevance score (0.0–1.0). Returns the top 5 candidates, re-sorted by cross-encoder score.

### 8.2: Multi-Signal Ranking

**File**: `MultiSignalRanker.java`
**What happens**: Applies a session boost — chunks from the current session get a soft multiplier to their score. This means "the PDF you just uploaded" ranks slightly higher than "a PDF someone uploaded last week", all else being equal.

**Output**: `List<RetrievalCandidate>` — top 5, finally ranked.

### 8.3: Quality Fallback Check

**File**: `RetrievalOrchestrator.fallbackQualityCheck()`
**What happens**: Evaluates the quality of the top candidates:
- If `topScore < threshold` AND the query was restricted to specific `targetDocuments`:
  - Rewrites the `RetrievalPlan` with `Scope.CORPUS` (global).
  - Re-runs the entire retrieval pipeline (Steps 7.1 → 8.2) against the global corpus.
  - Emits a `scope_expansion` SSE event to the frontend so it can show "Expanding search scope..."

**Output**: `RetrievalResult` with `evidence`, `visuals`, `requestedScope`, `actualScope`, `expandedScope`, `reason`.

---

### CASE B4c: ADAPTIVE Tier (Multi-Hop Loop)

**Example query**: `"What are all the environmental policies mentioned across all my documents?"`

**File**: `ContextBuilderService.orchestrateAdaptive()` → `adaptiveLoop()`

**Iteration 1**:
1. Creates a `StaticExecutionPlan` with the initial plan.
2. Calls `retrievalOrchestrator.orchestrate()` — goes through Steps 7.1 → 8.3.
3. Calls `retrievalOrchestrator.generateObservation()` — evaluates if evidence is sufficient:
   - `topScore > 0.8` → `EVIDENCE_FOUND`
   - `topScore > 0.5` → `PARTIAL_EVIDENCE`
   - Otherwise → `OUT_OF_SCOPE` or `EVIDENCE_MISSING`
4. Calls `retrievalController.decideNextAction(adaptivePlan, observation)`:

**File**: `RetrievalController.java`
**What happens**: Sends the goal, expected entities, and observation to an LLM:
```
GOAL: Adaptive retrieval for: What are all the environmental policies...
EXPECTED ENTITIES: environmental policies
OBSERVATION TYPE: PARTIAL_EVIDENCE
MESSAGE: Partial/weak evidence found
```

**LLM Output**:
```json
{
  "type": "SEARCH",
  "reasoning": "Found some policies but may be missing others. Need to search with broader terms.",
  "next_query": "government regulations sustainability standards carbon emissions policy"
}
```

5. If `type != STOP`, creates a new `RetrievalPlan` from `next_query` and loops back to step 1.

**Iteration 2**:
Same as above. If the controller returns `STOP` or `maxIterations` (2) is reached, all accumulated candidates from all iterations are merged.

**Output**: Combined `List<RetrievalCandidate>` from all iterations, deduplicated.

---

## Step 9: Evidence Structuring

**File**: `EvidenceStructuringService.java`
**What happens**: Takes the raw retrieval candidates + visual evidence and structures them into a readable context block:

1. **Visual Evidence** (if any): Formats each image with a display title extracted from its caption.
2. **Deduplication**: Removes duplicate chunks by ID.
3. **Grouping**: Groups chunks by `sourceName`.
4. **Adjacent Chunk Merging**: If consecutive `chunkIndex` values from the same source are found, merges them into one coherent passage.
5. **Chronological Detection**: If the content contains years/dates, sorts chronologically.
6. **Merge Operation**:
   - `UNION`: All evidence combined flat.
   - `COMPARE`: Evidence grouped by entity/source with headers like `=== Source: report_a.pdf ===`.
   - `NONE`: Simple concatenation.

**Output**: `AggregatedEvidence` containing `evidenceString` (the full context block) and `updatedVisuals`.

---

## Step 9.5: Final Prompt Assembly

**File**: `ContextBuilderService.buildPrompt()`
**What happens**: Assembles the final user prompt:
```
Context Information:
[The structured evidence block from Step 9]

Runtime Session Context:
Uploaded Files:
- report.pdf
- data.xlsx
Currently Active File in UI: report.pdf

Conversation History:
User: What is quantum computing?
Assistant: Quantum computing uses qubits...

User Question:
What does the report say about climate change?
```

**File**: `ContextBuilderService.getSystemPrompt()`
**What happens**: Selects the system prompt:
- If no evidence was found and no history → `generalPrompt.st` (generic helpful assistant).
- Otherwise → `ragPrompt.st` with `<inferenceDirective>` replaced based on strategy:
  - `CONCEPT_EXPANSION` → "Never say a concept is absent because the exact word is missing. Infer from underlying evidence."
  - `META_DOC_SEARCH` → "Identify WHICH documents are relevant. Do NOT require the exact query word to appear verbatim."
  - Default → "Be literal. Extrapolate nothing. If the exact phrase or fact is missing, state that it is not present."

**Output**: `ContextResult` containing `systemPrompt`, `prompt`, `documents`, `visuals`, `topScore`.

---

## Step 10: Generation

**File**: `LlmWorkerService.streamAnswer()`
**What happens**: 
1. Emits a `progress` SSE event: `"Generating final answer..."`
2. Calls the user's selected model (e.g., Gemini 2.5 Flash) via `ChatClient`:
   - System prompt: The RAG prompt with the inference directive.
   - User prompt: The assembled prompt from Step 9.5.
3. **Streams** each token as it arrives:
   - Each token → `publishToken(messageId, "message", token)` → Redis Pub/Sub channel `chat-tokens:{messageId}` → SSE to frontend.
   - Frontend appends each token in real-time.
4. Accumulates the full response in a `StringBuilder`.

**Output**: `fullResponse` (the complete generated answer).

---

## Step 11: Post-Processing

**File**: `LlmWorkerService.executeLlmGeneration()`

### 11.1: Citation Extraction

**File**: `CitationService.java`
**What happens**: Extracts citation metadata from the retrieved documents:
- `sourceName`, `sourceType`, `sourceUrl` (Cloudinary link to original file), `page`, `charStart`, `charEnd`, `boundingBoxes`.
- Publishes a `citations` SSE event with the JSON array.

### 11.2: Visual Evidence Publishing

**What happens**: If visual evidence exists, publishes a `visuals` SSE event with the image URLs and captions.

### 11.3: Message Persistence

**What happens**: Updates the `Message` entity in Postgres:
- `content` = full response text.
- `status` = `COMPLETED`.
- `citationsJson` = serialized citations.
- `visualsJson` = serialized visuals.

### 11.4: Stream Completion

**What happens**: Publishes `publishToken(messageId, "done", "")` — the frontend knows the response is complete.

### 11.5: Follow-Up Questions (Async)

**File**: `SuggestedQuestionsService.triggerFollowUpForSession()`
**What happens**: Fires off an async LLM call (Gemini 2.5 Flash Lite) to generate 3 follow-up questions based on the conversation so far. The result is cached in `SessionUploadState` and polled by the frontend separately.

### 11.6: Memory Gating

**File**: `MemoryGatingService.java`
**What happens**: Checks if this interaction is worth remembering:
- Message too short (< 4 words)? → Skip.
- Bot couldn't answer? → Skip.
- Meta/bot question? → Skip.
- Otherwise → `isMemorable = true`.

If `isMemorable && topScore >= 0.4`:
1. Creates a string: `"User: {query}\nAssistant: {fullResponse}"`.
2. Embeds it into `MessagesPineconeVectorStore` (Pinecone) with metadata `{ sessionId, conversationPairId, timestamp }`.
3. Appends the assistant response to the current episode via `topicShiftDetectorService.appendAssistantResponse()`.

---

## Memory Worker (Async Background)

**File**: `MemoryWorkerService.java`
**When**: Runs continuously as a background daemon, consuming the `memory_jobs` Redis Stream.

**What happens** when an episode is consumed:
1. Loads the session from Postgres.
2. Sends the episode content to an LLM:
   ```
   Summarize the following conversation episode.
   Extract the main topic, a concise summary, key entities,
   and assign an importance score (0.0 to 1.0).
   ```
3. **LLM Output**:
   ```json
   {
     "topic": "Black Holes and Event Horizons",
     "summary": "User asked about how black holes form and the properties of event horizons. Discussed Sagittarius A* and its mass.",
     "entities": ["black holes", "event horizon", "Sagittarius A*"],
     "keywords": ["formation", "gravity", "singularity"],
     "importanceScore": 0.75
   }
   ```
4. Saves to Postgres `ConversationTimeline` table (structured, queryable).
5. Embeds `"Episode: {topic}\n\n{summary}"` into Pinecone with metadata `{ type: EPISODE_SUMMARY, sessionId, topic }`.

---

## Complete Flow Summary Diagram

```
User Message
    │
    ▼
ChatService.java ──push──▶ Redis Stream: chat_jobs
                                │
                                ▼
                    LlmWorkerService.java (polls)
                                │
                    ┌───────────┤
                    ▼           │
    TopicShiftDetectorService   │ (LLM call #1: topic shift?)
                    │           │
                    ▼           │
    ContextBuilderService.java  │
          │                     │
          ▼                     │
    fetchHistoryBlock()         │ (Postgres: last 10 messages)
          │                     │
          ▼                     │
    PlannerService.java         │
          │                     │
          ├─── FastIntentService (regex) ───▶ Match? ──▶ DirectExecutionPlan ──▶ SKIP RETRIEVAL
          │                                                                          │
          └─── No Match ──▶ callUnifiedRouter() (LLM call #2: Gemini 3.1 Flash Lite) │
                                │                                                    │
                    ┌───────────┼───────────────┐                                    │
                    ▼           ▼               ▼                                    │
             Memory Intent   Standard       Adaptive                                 │
             (Timeline/       Query           Tier                                   │
              Episodic/     ┌───────┐    ┌──────────┐                                │
              Specific)     │       │    │          │                                 │
                │           ▼       │    ▼          │                                 │
                │    Orchestrator   │  adaptiveLoop │                                 │
                │           │       │    │          │                                 │
                │    ┌──────┴──┐    │    │   RetrievalController                      │
                │    ▼         ▼    │    │   (LLM call #3-4)                          │
                │  Text     Visual  │    │          │                                 │
                │  Hybrid   Hybrid  │    └──────────┘                                 │
                │    │         │    │         │                                       │
                │    ▼         │    │         │                                       │
                │  Pinecone ◄─┤    │         │                                       │
                │  Postgres ◄─┘    │         │                                       │
                │    │              │         │                                       │
                │    ▼              │         │                                       │
                │   RRF Fusion      │         │                                       │
                │    │              │         │                                       │
                │    ▼              │         │                                       │
                │  RerankService    │         │                                       │
                │    │              │         │                                       │
                │    ▼              │         │                                       │
                │  MultiSignalRanker│         │                                       │
                │    │              │         │                                       │
                │    ▼              │         │                                       │
                │  Quality Check ───┤         │                                       │
                │  (expand scope?)  │         │                                       │
                │         │         │         │                                       │
                ▼         ▼         ▼         ▼                                       │
            EvidenceStructuringService.java                                           │
                          │                                                           │
                          ▼                                                           │
                ContextBuilderService.buildPrompt()  ◄────────────────────────────────┘
                          │
                          ▼
                LlmWorkerService.streamAnswer()  (LLM call: user's selected model)
                          │
                    ┌─────┼──────────┐
                    ▼     ▼          ▼
              Stream   Citations   Visuals
              Tokens   Extraction  Publishing
                │         │          │
                ▼         ▼          ▼
            Redis Pub/Sub: chat-tokens:{messageId}
                          │
                          ▼
                    Frontend (SSE)
                          │
                    ┌─────┼──────────────────┐
                    ▼     ▼                  ▼
              MemoryGating  Follow-Up Qs   Message DB
              Service       (async LLM)    Update
                │
        ┌───────┴────────┐
        ▼                ▼
  Pinecone           TopicShift
  (User-Asst         (append to
   Pairs)             episode)
```
