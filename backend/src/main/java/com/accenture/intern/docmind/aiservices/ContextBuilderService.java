package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.EmbeddedDocument;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.service.SessionCacheService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ContextBuilderService {

    /**
     * How many of the most recent messages (user + assistant combined) to pull
     * into the prompt as short-term conversation history. Kept small on purpose:
     * this is just enough to resolve follow-ups / pronouns ("explain that more",
     * "what about the second one") without blowing up token usage on every turn.
     * 10 messages == last 5 user/assistant exchanges.
     */
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final HybridRetrievalService hybridRetrievalService;
    private final SessionCacheService sessionCacheService;
    private final RerankService rerankService;
    private final MessageRepository messageRepository;
    private final QueryIntentService queryIntentService;
    private final ObjectMapper objectMapper;
    private final String ragPrompt;
    private final String generalPrompt;

    public ContextBuilderService(
            HybridRetrievalService hybridRetrievalService,
            SessionCacheService sessionCacheService,
            RerankService rerankService,
            MessageRepository messageRepository,
            QueryIntentService queryIntentService,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/ragprompt.st") Resource ragPromptResource,
            @Value("classpath:prompts/generalprompt.st") Resource generalPromptResource) throws IOException {
        this.hybridRetrievalService = hybridRetrievalService;
        this.sessionCacheService = sessionCacheService;
        this.rerankService = rerankService;
        this.messageRepository = messageRepository;
        this.queryIntentService = queryIntentService;
        this.objectMapper = objectMapper;
        this.ragPrompt = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
        this.generalPrompt = StreamUtils.copyToString(generalPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    public Mono<ContextResult> buildContext(String question, Long sessionId) {
        // Fetched once up front and reused by whichever branch below ends up
        // building the final prompt, so every chat turn — RAG or general — gets
        // the same short-term memory of the session.
        Mono<String> historyMono = fetchHistoryBlock(sessionId);

        SessionUploadState state = sessionCacheService.getState(sessionId);
        List<EmbeddedDocument> embeddedDocs = state == null ? null : state.getEmbeddedDocuments();
        boolean hasCachedDocs = embeddedDocs != null && !embeddedDocs.isEmpty();

        // Source names of whatever was uploaded earlier in THIS session (if
        // anything) - passed down to fallbackRetrieve so a follow-up like
        // "compare these two" can be anchored to a fresh same-session upload
        // first, before falling back to whatever the previous answer happened
        // to cite. A literal recent upload is stronger evidence of what "these"
        // means than a citation list from however many turns ago.
        Set<String> sessionUploadSources = hasCachedDocs
                ? embeddedDocs.stream()
                    .map(EmbeddedDocument::getDocument)
                    .map(doc -> (String) doc.getMetadata().get("sourceName"))
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : Set.of();

        if (hasCachedDocs) {
            boolean isGeneralQuestion = isGeneralGreetingOrInquiry(question);
            if (isGeneralQuestion) {
                return fallbackRetrieve(question, sessionId, historyMono, sessionUploadSources);
            }

            // Two cases where the most-recently-uploaded file(s) in THIS session
            // should be answered from directly, bypassing the corpus-wide search
            // entirely, instead of letting a brand-new upload compete on ranking
            // against every other document anyone has ever uploaded (which is
            // what was causing "who is the person in this document" / "what is
            // this" right after an upload to either come back empty or answer
            // about the wrong file):
            //   1. Ingestion is still in flight (EMBEDDING/INGESTING) — there's
            //      nothing else searchable for this file yet anyway.
            //   2. The question is clearly a deictic reference to "the document
            //      I just uploaded" ("what is this", "describe this file", ...).
            //      The user has told us exactly which document they mean, so we
            //      honor that instead of a ranked guess across the whole corpus.
            boolean stillIndexing = state.getState() == UploadState.EMBEDDING
                    || state.getState() == UploadState.INGESTING;
            boolean referencesUploadedDoc = isUploadedDocumentReferenceQuestion(question);

            if (stillIndexing || referencesUploadedDoc) {
                // This path bypasses the whole-corpus search by design (see the
                // comment above), but two more pieces of the question's meaning
                // still need to be honored even when it's also a deictic
                // reference to "this document":
                //
                //   1. An exclusion clause - e.g. a session with two freshly-
                //      uploaded files where the user asks "summarize this
                //      except the cover letter". Without this, exclusion would
                //      only ever work when fallbackRetrieve is reached, doing
                //      nothing on this earlier-returning path instead - same
                //      shape as the original "except sql notes" bug, just on a
                //      different branch.
                //
                //   2. An explicitly-named comparison target - "compare this
                //      doc's project with flipkart project" contains both a
                //      deictic reference ("this" + "doc") AND a real document
                //      name ("flipkart"). Without this, the question matches
                //      the deictic check, routes to session-cache-only, and
                //      Flipkart - named right there in the question - never
                //      gets retrieved at all, which is what produced "I
                //      couldn't find relevant information" even though the
                //      user clearly named a real second document.
                Mono<Set<String>> excludedSourcesMono = queryIntentService.resolveExcludedSources(question);
                Mono<Set<String>> comparisonTargetMono = queryIntentService.resolveNamedComparisonTarget(question);

                return Mono.zip(excludedSourcesMono, comparisonTargetMono)
                        .flatMap(pair -> {
                            Set<String> excludedSources = pair.getT1();
                            Set<String> comparisonTargets = pair.getT2();

                            List<Document> selectedDocs = embeddedDocs.stream()
                                    .map(EmbeddedDocument::getDocument)
                                    .filter(doc -> excludedSources.isEmpty()
                                            || !excludedSources.contains(doc.getMetadata().get("sourceName")))
                                    .toList();

                            Mono<List<Document>> comparisonDocsMono = comparisonTargets.isEmpty()
                                    ? Mono.just(List.<Document>of())
                                    : hybridRetrievalService.fetchSourcesByName(comparisonTargets);

                            return comparisonDocsMono.flatMap(comparisonDocs -> {
                                List<Document> combinedDocs = new ArrayList<>(selectedDocs);
                                combinedDocs.addAll(comparisonDocs);

                                int estimatedTokens = combinedDocs.stream()
                                        .mapToInt(doc -> doc.getText().length() / 4)
                                        .sum();
                                log.info("Using {} session-cache chunks + {} comparison-target chunks ({} tokens total) (stillIndexing={}, referencesUploadedDoc={}, excluding={}, comparisonTarget={})",
                                        selectedDocs.size(), comparisonDocs.size(), estimatedTokens,
                                        stillIndexing, referencesUploadedDoc, excludedSources, comparisonTargets);

                                return historyMono.map(historyBlock -> new ContextResult(
                                        combinedDocs.isEmpty() ? generalPrompt : ragPrompt,
                                        buildPrompt(question, buildContextBlock(combinedDocs), historyBlock),
                                        combinedDocs
                                ));
                            });
                        });
            }
        }

        return fallbackRetrieve(question, sessionId, historyMono, sessionUploadSources);
    }

    /**
     * Matches questions that clearly refer to "the document/file I just
     * uploaded" without necessarily naming it — e.g. "what is this", "describe
     * this document", "explain the given doc", "tell me about this file",
     * "summarize it". Whole-corpus ranked search has no way to resolve a
     * deictic "this"/"it"/"the given one" — it can only match words — so these
     * questions need to be routed straight at the most-recently-uploaded
     * file(s) in this session rather than rolled into the same ranking as every
     * other chunk in the shared corpus.
     * <p>
     * Deliberately keyword-set based rather than a single adjacency regex
     * (e.g. requiring "this document" as one phrase) — real phrasing puts
     * other words in between ("the given doc", "this uploaded file"), so the
     * check is: does the question contain *some* deictic/reference word, and
     * *some* generic document-type word, anywhere in it (not necessarily next
     * to each other)?
     */
    private static final java.util.Set<String> DEICTIC_WORDS = java.util.Set.of(
            "this", "that", "it", "given", "uploaded", "attached", "above", "provided");

    private static final java.util.Set<String> DOC_TYPE_WORDS = java.util.Set.of(
            "document", "documents", "doc", "docs", "file", "files", "pdf",
            "image", "picture", "photo", "attachment", "upload", "paper", "resume");

    private boolean isUploadedDocumentReferenceQuestion(String question) {
        String q = question.toLowerCase().trim();

        // "what is this", "what's this", "what is this about", "whats in this"
        if (q.matches("^(what('?s| is)?)\\s+(is\\s+)?this\\b.*")) {
            return true;
        }

        java.util.Set<String> words = java.util.Arrays.stream(q.split("[^a-z0-9]+"))
                .filter(w -> !w.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        boolean hasDeictic = words.stream().anyMatch(DEICTIC_WORDS::contains);
        boolean hasDocType = words.stream().anyMatch(DOC_TYPE_WORDS::contains);

        // "explain the given doc", "who is the person in this document",
        // "this attached file" — deictic + doc-type word anywhere in the
        // question, regardless of order or what's between them.
        if (hasDeictic && hasDocType) {
            return true;
        }

        // "summarize it", "describe that", "tell me about this" — a
        // summarizing verb plus a deictic word, even with no doc-type noun at
        // all (the noun is implied: "it" == "the thing I just uploaded").
        boolean isSummarizingVerbPhrase = q.matches("^(describe|explain|summari[sz]e|analyse|analyze)\\b.*")
                || q.startsWith("tell me about");
        if (isSummarizingVerbPhrase && hasDeictic) {
            return true;
        }

        return false;
    }

    private Mono<ContextResult> fallbackRetrieve(String question, Long sessionId, Mono<String> historyMono, Set<String> sessionUploadSources) {
        long t0 = System.currentTimeMillis();

        Mono<Set<String>> excludedSourcesMono = queryIntentService.resolveExcludedSources(question);

        // Three ways a question can need to be anchored to specific document(s)
        // instead of an ordinary fresh corpus-wide ranked search, checked in
        // priority order:
        //
        //   1. Both sides of a comparison are named directly in the question
        //      itself - "distinguish hitesh and tejesh", "difference between
        //      hitesh resume and tejesh resume". This is the strongest signal:
        //      the user told us exactly which two (or more) documents they
        //      mean, by name, in this very message - no guessing needed.
        //   2. A pronoun-only follow-up ("compare these two", "common tech
        //      stack between both") names no document itself - it only makes
        //      sense anchored to *something* the conversation was already
        //      about:
        //        a. A fresh upload earlier in THIS session, if any - if the
        //           user just uploaded two files and says "compare these
        //           two", that's almost certainly what "these" means.
        //        b. Otherwise, the sources cited in the most recent assistant
        //           answer that actually had citations (see
        //           lastAssistantAnswerSources, which skips past uncited
        //           turns like "thanks!" so a follow-up several turns later
        //           still resolves correctly).
        //   3. Neither of the above - ordinary ranked/whole-document search,
        //      unchanged.
        //
        // Without (1), a question naming two real documents directly but with
        // no recognized trigger word in COMPARISON_INTENT_PATTERN's narrower
        // sibling (just "compare"/"vs") would fall through to ordinary ranked
        // search and the two named documents would have to compete for a few
        // slots like everything else - the same failure shape as the original
        // Zomato/Walmart bug, just triggered by vocabulary ("distinguish",
        // "difference between") the system didn't recognize as a comparison
        // at all.
        Mono<Set<String>> comparisonSubjectsMono = queryIntentService.resolveComparisonSubjects(question);

        Mono<Set<String>> followUpScopedSourcesMono;
        if (!queryIntentService.isFollowUpSourceReference(question)) {
            followUpScopedSourcesMono = Mono.just(Set.of());
        } else if (!sessionUploadSources.isEmpty()) {
            followUpScopedSourcesMono = Mono.just(sessionUploadSources);
        } else {
            followUpScopedSourcesMono = lastAssistantAnswerSources(sessionId);
        }

        Mono<Set<String>> scopedSourcesMono = Mono.zip(comparisonSubjectsMono, followUpScopedSourcesMono)
                .map(pair -> pair.getT1().isEmpty() ? pair.getT2() : pair.getT1());

        return Mono.zip(excludedSourcesMono, scopedSourcesMono, historyMono)
                .flatMap(triple -> {
                    Set<String> excludedSources = triple.getT1();
                    Set<String> scopedSources = triple.getT2();
                    String historyBlock = triple.getT3();

                    return hybridRetrievalService.retrieve(question, sessionId, excludedSources, scopedSources)
                            .flatMap(candidates -> buildContextResultFromCandidates(question, historyBlock, candidates, t0));
                });
    }

    private Mono<ContextResult> buildContextResultFromCandidates(String question, String historyBlock, List<Document> candidates, long t0) {
        long tRetrieveDone = System.currentTimeMillis();
        log.info("[TIMING] hybrid retrieval (dense+keyword, parallel): {}ms", tRetrieveDone - t0);

        // Whole-document retrieval already decided exactly which chunks belong
        // in context (every chunk of the one matched source, in document
        // order) - running them through the cross-encoder rerank would just
        // truncate back down to topN=5 by relevance-to-query-wording, which is
        // the same "missing sections" problem whole-document mode exists to
        // fix. So this path bypasses rerank entirely and uses all of them.
        // This also covers the scoped-multi-source case (a follow-up like
        // "compare these two"), which is tagged the same way.
        boolean isWholeDocument = !candidates.isEmpty()
                && Boolean.TRUE.equals(candidates.get(0).getMetadata().get("wholeDocumentMatch"));

        if (isWholeDocument) {
            log.info("[TIMING] whole-document mode: skipping rerank, using all {} chunks", candidates.size());
            String contextBlock = buildContextBlock(candidates);
            String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock);
            return Mono.just(new ContextResult(ragPrompt, augmentedPrompt, candidates));
        }

        // rerank() runs a CPU-bound ONNX cross-encoder loop (tens of
        // sequential inferences). That must never run on a Netty event-loop
        // thread - it would stall every other in-flight request sharing that
        // loop, not just this one. Pushed onto boundedElastic, same as every
        // other blocking call in this class (JPA history fetch, etc).
        return Mono.fromCallable(() -> rerankService.rerank(question, candidates, 5))
                .subscribeOn(Schedulers.boundedElastic())
                .map(rerankedDocs -> {
                    long tRerankDone = System.currentTimeMillis();
                    log.info("[TIMING] rerank ({} candidates -> {}): {}ms",
                            candidates.size(), rerankedDocs.size(), tRerankDone - tRetrieveDone);
                    log.info("[TIMING] total buildContext: {}ms", tRerankDone - t0);

                    String contextBlock = buildContextBlock(rerankedDocs);
                    String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock);
                    String systemPrompt = rerankedDocs.isEmpty() ? generalPrompt : ragPrompt;
                    return new ContextResult(systemPrompt, augmentedPrompt, rerankedDocs);
                });
    }

    /**
     * Pulls the sourceName of every citation attached to ANY recent ASSISTANT
     * message within the last MAX_HISTORY_MESSAGES messages, unioned together
     * - not just the single most recent cited turn. Used to resolve "these
     * two" / "both" / "they" in a follow-up question to the actual documents
     * the conversation was about, rather than re-guessing from the follow-up's
     * own (typically document-name-free) wording.
     * <p>
     * Unioning across turns (rather than stopping at the first cited one) is
     * what makes a sequence like "tell about Zomato" -> [cites Zomato] ->
     * "tell about Flipkart" -> [cites Flipkart] -> "common tech stack between
     * these two" resolve correctly: the immediately preceding turn only cited
     * Flipkart, so stopping there would silently drop Zomato and reproduce
     * the exact original bug in a different shape. Capped at the same history
     * window the rest of this class already uses for short-term memory, so it
     * stays bounded to "recent" rather than scanning the whole session.
     * <p>
     * Returns an empty set - meaning "no scoping, fall back to normal
     * corpus-wide search" - if no assistant message in the window has any
     * citations at all (e.g. the conversation so far has been entirely
     * general/greeting exchanges).
     */
    private Mono<Set<String>> lastAssistantAnswerSources(Long sessionId) {
        return Mono.fromCallable(() -> messageRepository
                        .findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(recentDesc -> {
                    Set<String> sources = new LinkedHashSet<>();
                    for (Message m : recentDesc) { // newest-first; order doesn't matter since we union everything
                        if (m.getRole() != MessageRole.ASSISTANT || m.getCitationsJson() == null) {
                            continue;
                        }
                        try {
                            JsonNode citations = objectMapper.readTree(m.getCitationsJson());
                            for (JsonNode citation : citations) {
                                JsonNode sourceNameNode = citation.get("sourceName");
                                if (sourceNameNode != null && !sourceNameNode.isNull()) {
                                    sources.add(sourceNameNode.asText());
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse citationsJson for session {} while resolving follow-up source reference: {}",
                                    sessionId, e.getMessage());
                            // malformed JSON on this one turn - skip it, keep unioning the rest
                        }
                    }
                    return sources;
                })
                .onErrorResume(e -> {
                    log.warn("Failed to load recent messages for session {}: {}", sessionId, e.getMessage());
                    return Mono.just(Set.of());
                });
    }

    /**
     * [CITE:n] ids are only ever meaningful within the single turn that
     * produced them - they're 1-indexed positions into THAT turn's
     * <CITATION id="n"> context block, which is rebuilt from scratch (often
     * with a different document count and order) on every turn. Stripped out
     * of assistant messages before they go into the history transcript so the
     * model isn't shown its own prior "[CITE:7]" text sitting right there in
     * context - a small/fast model can and does pattern-match and reuse a
     * citation number it just saw nearby, even when this turn's actual
     * context block has nothing at that position. That's what produced an
     * inline [CITE:7] with no matching card: the model echoed a stale id from
     * a previous turn's history instead of one from the current retrieval.
     * <p>
     * Matches both the single-id shape the prompt asks for ([CITE:7]) and the
     * comma-separated multi-id shape the model sometimes produces instead
     * ([CITE:1, 4]) - mirrors CitationService.CITE_TAG_PATTERN, which needed
     * the same fix for the same reason (a single \d+ pattern would leave the
     * multi-id variant completely unstripped, bracket and all, sitting in the
     * history transcript).
     */
    private static final java.util.regex.Pattern HISTORY_CITE_TAG_PATTERN = java.util.regex.Pattern.compile("\\[CITE:\\s*[\\d,\\s]+?\\s*]");

    /**
     * Pulls the last MAX_HISTORY_MESSAGES messages for the session (run on a
     * blocking-friendly scheduler since it's a JPA call) and formats them into a
     * simple "Role: text" transcript, oldest first. Returns an empty string if the
     * session has no prior turns yet (first message of the conversation).
     */
    private Mono<String> fetchHistoryBlock(Long sessionId) {
        return Mono.fromCallable(() -> {
                    List<Message> recent = messageRepository
                            .findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId);
                    if (recent.isEmpty()) {
                        return "";
                    }
                    List<Message> chronological = new ArrayList<>(recent);
                    Collections.reverse(chronological);

                    StringBuilder sb = new StringBuilder();
                    for (Message m : chronological) {
                        String role = m.getRole() == MessageRole.USER ? "User" : "Assistant";
                        String content = m.getContent() == null ? "" : m.getContent().trim();
                        if (content.isEmpty()) {
                            continue;
                        }
                        if (m.getRole() == MessageRole.ASSISTANT) {
                            content = HISTORY_CITE_TAG_PATTERN.matcher(content).replaceAll("").trim();
                            // Stripping leaves behind double spaces (tags were often
                            // back-to-back or followed by a space) and a stray space
                            // before trailing punctuation ("models ." instead of
                            // "models."). Tidied up so the history transcript reads
                            // naturally rather than visibly mangled.
                            content = content.replaceAll("\\s+", " ").replaceAll("\\s+([.,;:!?])", "$1").trim();
                        }
                        sb.append(role).append(": ").append(content).append("\n");
                    }
                    return sb.toString().trim();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to load conversation history for session {}: {}", sessionId, e.getMessage());
                    return Mono.just("");
                });
    }

    private boolean isGeneralGreetingOrInquiry(String question) {
        String q = question.toLowerCase();
        return q.matches("^(hi|hello|hey|greetings|how are you|what can you do).*");
    }

    private String buildContextBlock(List<Document> docs) {
        if (docs.isEmpty()) {
            return "No relevant documents found.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document doc : docs) {
            String name = (String) doc.getMetadata().getOrDefault("sourceName", "unknown");
            String type = (String) doc.getMetadata().getOrDefault("sourceType", "");
            int chunk = ((Number) doc.getMetadata().getOrDefault("chunkIndex", 0)).intValue();

            sb.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s | Chunk: %d\n%s\n</CITATION>\n\n",
                    index++, name, type, chunk, doc.getText()));
        }
        return sb.toString().trim();
    }

    private String buildPrompt(String question, String context, String historyBlock) {
        String historySection = (historyBlock == null || historyBlock.isBlank())
                ? ""
                : """
                  CONVERSATION HISTORY (most recent turns in this session, oldest first — use this only to understand what the user is referring to, e.g. follow-ups, pronouns, "the previous one"; it is not a source of facts):
                  %s

                  ---

                  """.formatted(historyBlock);

        return """
                %sCONTEXT (from uploaded documents):
                %s

                ---

                TASK: %s
                """.formatted(historySection, context, question);
    }
}
