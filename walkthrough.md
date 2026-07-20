# Evaluation Framework Architecture & Usage

I have successfully built out the deterministic evaluation framework into the backend exactly as discussed in the roadmap!

## How It Is Used
The evaluation suite runs directly within the Spring Boot application, reusing the exact same `PlannerService` and `RetrievalOrchestrator` that power production. This guarantees that evaluation results perfectly mirror what real users experience.

You can trigger and interact with it using simple REST endpoints. This makes it trivial to hook up a CI/CD pipeline (like GitHub Actions) to run a benchmark every time a PR is opened.

1. **Trigger an Evaluation Run**
   ```bash
   POST http://localhost:8080/evaluation/run
   ```
   *This executes the golden dataset of queries sequentially, applies the deterministic assertions, and returns a JSON report containing the `runId`, the pass rate, and the granular metrics (latency, chunks, etc.) for each query.*

2. **View Evaluation History**
   ```bash
   GET http://localhost:8080/evaluation/history
   ```
   *Returns a summary of all past runs in memory, showing their total queries and pass rate score.*

3. **View Specific Run Details**
   ```bash
   GET http://localhost:8080/evaluation/results/{runId}
   ```
   *Returns the exact `EvaluationResult` array for a specific run, detailing exactly which query failed and why (e.g., "Missing required document Loki").*

## How It Is Stored

For this initial prototype (Phase 1-3), the storage is **lightweight and in-memory**:

1. **The Golden Dataset**: Stored programmatically in `BenchmarkRepository.java`. We started with a handful of hardcoded `BenchmarkQuery` objects to represent the different categories (Comparison, Single-document, Concept Expansion).
2. **The Results History**: Stored in a `ConcurrentHashMap<String, List<EvaluationResult>>` inside `EvaluationController.java`. 

### Future Storage Evolution
Once you expand the dataset to the full 50 queries (Phase 2), we will want to migrate this to persistent storage:
- **Queries**: Moved to a `benchmark_queries.json` file in `src/main/resources` or a Postgres `benchmark_query` table so they can be modified without recompiling Java.
- **Results**: Moved to a Postgres `evaluation_run` and `evaluation_result` table. This is what will enable the **Historical Dashboard** you envisioned, allowing you to plot `Recall@20` and `Latency` over months of development!
