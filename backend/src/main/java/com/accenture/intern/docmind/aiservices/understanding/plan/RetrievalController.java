package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RetrievalController {
    
    private final ChatClient chatClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RetrievalController(ChatClient.Builder chatClientBuilder, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public Mono<AdaptiveAction> decideNextAction(AdaptiveExecutionPlan plan, RetrievalObservation observation) {
        // Evaluate the goal and observation, and decide what to do next.
        String prompt = """
                You are the Retrieval Controller for an agentic RAG system.
                Your task is to review the most recent Retrieval Observation against the overall Goal.
                
                GOAL: %s
                EXPECTED ENTITIES: %s
                
                OBSERVATION TYPE: %s
                MESSAGE: %s
                
                Decide the next action to take:
                - SEARCH: If a new query is needed to find missing evidence.
                - REFINE: If you need to filter or refine the search terms.
                - EXPAND: If you need to expand search to other concepts.
                - SWITCH_SOURCE: If you need to search a different document.
                - STOP: If the goal is met or the max iterations are reached.
                
                Respond in valid JSON format:
                {
                  "type": "STOP | SEARCH | REFINE | EXPAND | SWITCH_SOURCE",
                  "reasoning": "Why you chose this action",
                  "next_query": "The string to search if type != STOP, else null"
                }
                """.formatted(
                        plan.goal(), 
                        String.join(", ", plan.expectedEntities()),
                        observation.type().name(),
                        observation.message()
                );
        
        return Mono.fromCallable(() -> {
            String rawResponse = chatClient.prompt().user(prompt).call().content();
            try {
                String jsonToParse = rawResponse;
                int start = rawResponse.indexOf("{");
                int end = rawResponse.lastIndexOf("}");
                if (start != -1 && end != -1 && end > start) {
                    jsonToParse = rawResponse.substring(start, end + 1);
                }
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(jsonToParse);
                String actionTypeStr = node.path("type").asText();
                String reasoning = node.path("reasoning").asText();
                String nextQuery = node.path("next_query").asText(null);
                
                AdaptiveAction.AdaptiveActionType type = AdaptiveAction.AdaptiveActionType.valueOf(actionTypeStr);
                
                java.util.Optional<RetrievalPlan> nextPlan = java.util.Optional.empty();
                if (type != AdaptiveAction.AdaptiveActionType.STOP && nextQuery != null) {
                    // Create a simple plan for the next iteration based on the controller's query
                    nextPlan = java.util.Optional.of(new RetrievalPlan(
                            com.accenture.intern.docmind.aiservices.understanding.Intent.DOCUMENT_QA,
                            com.accenture.intern.docmind.aiservices.understanding.Scope.CORPUS,
                            com.accenture.intern.docmind.aiservices.understanding.RetrievalStrategy.SINGLE_SOURCE,
                            com.accenture.intern.docmind.aiservices.understanding.RetrievalExecutionMode.RANKED_RETRIEVAL,
                            java.util.List.of(),
                            java.util.List.of(),
                            nextQuery,
                            java.util.List.of(),
                            java.util.List.of()
                    ));
                }
                
                return new AdaptiveAction(type, reasoning, nextPlan);
            } catch (Exception e) {
                return new AdaptiveAction(AdaptiveAction.AdaptiveActionType.STOP, "Parse failed, stopping", java.util.Optional.empty());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
