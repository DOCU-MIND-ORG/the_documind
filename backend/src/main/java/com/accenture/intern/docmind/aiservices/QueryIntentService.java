package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses two kinds of retrieval-narrowing intent out of a raw user question,
 * neither of which the embedding model or BM25 can express on their own:
 * <p>
 * 1. EXCLUSION — "... except the SQL notes pdf", "not from the resume",
 *    "excluding Walmart doc". A query like this still embeds as topically
 *    *about* the excluded document (the word "except" doesn't push the
 *    embedding away from anything), and BM25 only ever adds positive term
 *    weight, so without an explicit parse the excluded source keeps getting
 *    retrieved anyway — which was exactly bug #2.
 * <p>
 * 2. FOLLOW-UP SOURCE REFERENCE — "compare these two", "common tech stack
 *    between both companies", "what do they have in common". These contain no
 *    document name at all, so a fresh corpus-wide search has no anchor and
 *    just ranks by generic relevance to words like "common"/"compare" — which
 *    is what let an unrelated third document (Walmart) outrank the two
 *    documents the conversation was actually about (Zomato/Flipkart). This
 *    parser only flags *that* the question is this kind of reference; the
 *    caller (ContextBuilderService) is responsible for resolving "these"/
 *    "both" to actual source names using the citations of the immediately
 *    preceding assistant turn.
 */
@Slf4j
@Service
public class QueryIntentService {

    /**
     * Minimum length for a word extracted from the exclusion clause to be
     * trusted as part of a filename match. Mirrors HybridRetrievalService's
     * MIN_SOURCE_MATCH_WORD_LENGTH so "the", "pdf", "doc" etc. alone can't
     * accidentally match every source.
     */
    private static final int MIN_MATCH_WORD_LENGTH = 4;

    /**
     * Matches the exclusion clause itself and captures the text describing
     * *what* is excluded, so we only fuzzy-match source filenames against
     * that fragment rather than the whole question (which would risk
     * matching the wrong document if the excluded doc's name shares a word
     * with the actual topic of the question).
     * <p>
     * Covers: "except X", "excluding X", "not from X", "not including X",
     * "other than X", "apart from X", "without X" — these connectives are
     * essentially never used in English for anything other than exclusion,
     * so they're matched anywhere in the question.
     */
    private static final Pattern EXCLUSION_PATTERN = Pattern.compile(
            "(?:except(?:\\s+for)?|excluding|not\\s+(?:from|including|in)|other\\s+than|apart\\s+from|without)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * "Skip X" / "ignore X" are NOT matched anywhere - unlike the connectives
     * above, both are ordinary verbs as often as they're exclusion
     * connectives ("the resume mentions skip level meetings, what is that?"
     * - "skip" here is just a word being discussed, not an instruction).
     * Matching them mid-clause risked excluding a document whose name
     * happened to share a word with whatever the sentence was actually
     * about. Restricting them to a clause boundary - start of the message,
     * or right after a comma/"and"/"but"/"please" - keeps the legitimate
     * instructional use ("list the names, skip the SQL notes one") while
     * dropping the false-positive risk of catching the word mid-sentence.
     */
    private static final Pattern RISKY_EXCLUSION_VERBS_AT_BOUNDARY = Pattern.compile(
            "(?:^|,\\s*|\\band\\b\\s*|\\bbut\\b\\s*|\\bplease\\b\\s*)(?:skip(?:ping)?|ignor(?:e|ing))\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Trims trailing filler words ("pdf", "doc", "document", "file") and
     * leading articles from a captured exclusion fragment so the remaining
     * words are the most distinctive ones for filename matching.
     */
    private static final Set<String> FILLER_WORDS = Set.of(
            "the", "a", "an", "any", "this", "that", "those", "these",
            "pdf", "doc", "docs", "document", "documents", "file", "files",
            "from", "for", "in", "of");

    /**
     * Question contains a comparison/follow-up word AND no clear document
     * name of its own — signals "resolve this against whatever the last
     * answer was about" rather than "search the whole corpus fresh".
     */
    private static final Pattern FOLLOWUP_REFERENCE_PATTERN = Pattern.compile(
            "\\b(these\\s+(two|both)|both\\s+(of\\s+them|companies|projects|documents|docs)|the\\s+two|" +
            "common\\s+(tech|technolog|stack)|in\\s+common|they\\s+have|between\\s+them|compare\\s+(them|these|those))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Broad trigger vocabulary for "the user wants two (or more) specific
     * things compared against each other" - deliberately wider than just
     * "compare"/"vs"/"versus": "distinguish X and Y", "difference between X
     * and Y", "differentiate X from Y", "how does X differ from Y", "contrast
     * X with Y" are all the same underlying request phrased differently, and
     * a user has no reason to know which exact verb this system happens to
     * special-case. This pattern only flags that the question HAS comparison
     * intent somewhere in it; it deliberately does NOT try to carve out "the
     * other side" as a substring the way COMPARISON_WITH_NAMED_TARGET does,
     * because with no deictic anchor there's no "first side" to anchor
     * against - both names need to be found by matching the WHOLE question
     * against the corpus, not by extracting one trailing fragment.
     * <p>
     * Firing on this pattern alone is harmless by design: it only changes
     * retrieval once paired with resolveComparisonSubjects() actually finding
     * 2+ distinctively-matched corpus filenames in the same question. A
     * generic, document-free use of "difference" or "contrast" (e.g. "what's
     * the difference between REST and GraphQL") matches zero filenames and
     * falls through to ordinary ranked search exactly as before.
     */
    private static final Pattern COMPARISON_INTENT_PATTERN = Pattern.compile(
            "\\b(compare|distinguish|differentiate|contrast|differen(?:ce|t)|differ|vs\\.?|versus)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Captures the trailing fragment of a comparison that names its OTHER
     * side explicitly — "compare this doc's project WITH FLIPKART PROJECT",
     * "how does this compare TO WALMART", "this VS the zomato one". Two
     * shapes: "compare ... with/to/against/and X" (compare needs a
     * connective before its target), or "X vs/versus Y" (vs/versus is
     * itself the connective, no separate "compare" required).
     * <p>
     * This is the asymmetric case where one side of the comparison is a
     * deictic upload reference ("this doc") and the other is a real name the
     * corpus might have a file for. Without parsing this out, a question like
     * "compare this doc's project with flipkart project" gets classified as a
     * pure deictic reference (it contains "this" + "doc"), routes to the
     * session-cache-only fast path, and the named second document - Flipkart
     * - never gets retrieved at all, even though the user explicitly asked
     * for it by name. Used only from the deictic branch in
     * ContextBuilderService; the no-deictic, both-sides-named case ("compare
     * hitesh and tejesh") goes through resolveComparisonSubjects instead.
     */
    private static final Pattern COMPARISON_WITH_NAMED_TARGET = Pattern.compile(
            "(?:\\bcompare\\b.*?\\b(?:with|to|against|and)\\s+(.+)$|\\b(?:vs\\.?|versus)\\s+(.+)$)",
            Pattern.CASE_INSENSITIVE);

    private final DocumentChunkRepository documentChunkRepository;
    private final HybridRetrievalService hybridRetrievalService;

    public QueryIntentService(DocumentChunkRepository documentChunkRepository, HybridRetrievalService hybridRetrievalService) {
        this.documentChunkRepository = documentChunkRepository;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * Resolves any exclusion clause in the question ("... except the SQL
     * notes pdf") to the exact sourceName(s) it refers to, by fuzzy word
     * matching against every filename currently in the corpus — the same
     * approach HybridRetrievalService already uses to detect "tell about
     * Hitesh" -> "Hitesh_Resume.pdf". Returns an empty set if the question has
     * no exclusion clause, or the clause's words match no known source.
     * <p>
     * Unlike whole-document mode's "ambiguous -> fall back" rule, if the
     * clause matches MULTIPLE sources, all of them are excluded rather than
     * none. For exclusion specifically, that's the safer failure mode: the
     * user explicitly asked not to see something, so over-excluding (a
     * source they didn't quite mean gets dropped too) is a smaller problem
     * than under-excluding (the thing they asked to exclude shows up anyway).
     */
    public Mono<Set<String>> resolveExcludedSources(String question) {
        String trimmed = question.trim();
        Matcher exclusionMatch = EXCLUSION_PATTERN.matcher(trimmed);
        Matcher matchedMatcher;
        if (exclusionMatch.find()) {
            matchedMatcher = exclusionMatch;
        } else {
            Matcher riskyMatch = RISKY_EXCLUSION_VERBS_AT_BOUNDARY.matcher(trimmed);
            if (riskyMatch.find()) {
                matchedMatcher = riskyMatch;
            } else {
                return Mono.just(Set.of());
            }
        }

        // Drop anything after the exclusion fragment that reads like a new
        // clause of its own ("except the SQL notes pdf and tell me about Y")
        // by cutting at common conjunctions, so we don't pull unrelated words
        // from later in the sentence into the filename match. Captured by the
        // lambda below, so this has to be its own final variable rather than
        // reassigning the matched-group string in place.
        final String clause = matchedMatcher.group(1).split("\\b(and|but|then|,)\\b", 2)[0];

        Set<String> clauseWords = wordsOf(clause).stream()
                .filter(w -> !FILLER_WORDS.contains(w))
                .filter(w -> w.length() >= MIN_MATCH_WORD_LENGTH)
                .collect(Collectors.toSet());

        if (clauseWords.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.fromCallable(documentChunkRepository::findDistinctSourceNames)
                .subscribeOn(Schedulers.boundedElastic())
                .map(sourceNames -> {
                    Set<String> matched = new LinkedHashSet<>();
                    for (String sourceName : sourceNames) {
                        if (sourceName == null) continue;
                        Set<String> sourceWords = wordsOf(sourceName);
                        boolean overlaps = clauseWords.stream().anyMatch(sourceWords::contains);
                        if (overlaps) {
                            matched.add(sourceName);
                        }
                    }
                    if (!matched.isEmpty()) {
                        log.info("Exclusion clause '{}' matched source(s) to exclude: {}", clause.trim(), matched);
                    }
                    return matched;
                });
    }

    /**
     * True when the question has comparison intent ANY of the broad
     * COMPARISON_INTENT_PATTERN ways - "compare", "distinguish", "difference
     * between", "differentiate", "differ from", "contrast", "vs"/"versus".
     */
    private boolean hasComparisonIntent(String question) {
        return COMPARISON_INTENT_PATTERN.matcher(question).find();
    }

    /**
     * Resolves a comparison question that names BOTH sides directly with no
     * deictic anchor - "distinguish hitesh and tejesh", "what's the
     * difference between hitesh resume and tejesh resume", "how does hitesh
     * differ from tejesh" - to every corpus filename the question
     * distinctively matches, so a guaranteed whole-document fetch can be done
     * for ALL of them together rather than letting them compete for a few
     * slots in an ordinary ranked search.
     * <p>
     * Unlike resolveNamedComparisonTarget (which extracts one trailing
     * fragment as "the other side", assuming a deictic "this" already
     * anchors the first side), this has no anchor to extract a fragment
     * relative to - "hitesh" and "tejesh" are just two names sitting in the
     * question with no structural marker saying which is "first" and which is
     * "second". So instead of extracting a substring, this matches the WHOLE
     * question's words against every corpus filename and collects every
     * filename with a distinctive hit - naturally generalizing to 2, 3, or
     * more named subjects without needing to know the count in advance.
     * <p>
     * Returns an empty set whenever fewer than 2 sources get a distinctive
     * match: zero matches means the named entities aren't in the corpus at
     * all (nothing to scope to, fall back to ranked search as before); even
     * a single match isn't actually useful here since a real comparison needs
     * at least two things to compare - one match alone is the same situation
     * as an ordinary single-document question and is better left to
     * findUniquelyMatchedSource's whole-document path instead.
     */
    public Mono<Set<String>> resolveComparisonSubjects(String question) {
        if (!hasComparisonIntent(question)) {
            return Mono.just(Set.of());
        }

        Set<String> queryWords = wordsOf(question).stream()
                .filter(w -> w.length() >= MIN_MATCH_WORD_LENGTH)
                .collect(Collectors.toSet());

        if (queryWords.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.fromCallable(documentChunkRepository::findDistinctSourceNames)
                .subscribeOn(Schedulers.boundedElastic())
                .map(sourceNames -> {
                    Set<String> matched = hybridRetrievalService.findDistinctivelyMatchedSources(queryWords, sourceNames, Set.of());
                    if (matched.size() >= 2) {
                        log.info("Comparison question matched {} sources directly: {}", matched.size(), matched);
                        return matched;
                    }
                    return Set.<String>of();
                });
    }

    /**
     * Resolves the explicitly-named OTHER side of a comparison - "compare
     * this doc's project with flipkart project" -> matches whatever corpus
     * filename "flipkart" distinctively identifies - by fuzzy word matching,
     * same approach as resolveExcludedSources. Returns an empty set if the
     * question has no comparison-with-named-target shape, or if the named
     * fragment doesn't distinctively match exactly one corpus filename.
     * <p>
     * Deliberately requires an EXACT single match here (unlike
     * resolveExcludedSources' "match all, since over-excluding is safe"
     * rule) - the asymmetric risk runs the other way for a comparison
     * target: pulling in an extra, wrong document as something to "compare
     * against" pollutes the answer with unrelated content, whereas under-
     * matching just leaves the comparison one-sided, which is the same
     * "couldn't find it" outcome the user already sees today and can rephrase
     * from, not a new failure mode.
     */
    public Mono<Set<String>> resolveNamedComparisonTarget(String question) {
        Matcher matcher = COMPARISON_WITH_NAMED_TARGET.matcher(question.trim());
        if (!matcher.find()) {
            return Mono.just(Set.of());
        }

        String target = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        // Cut at a trailing conjunction the same way resolveExcludedSources
        // does, so "with flipkart project and also tell me about Y" doesn't
        // pull "tell me about Y" into the filename match.
        final String clause = target.split("\\b(and|but|then|,)\\b", 2)[0];

        Set<String> clauseWords = wordsOf(clause).stream()
                .filter(w -> !FILLER_WORDS.contains(w))
                .filter(w -> w.length() >= MIN_MATCH_WORD_LENGTH)
                .collect(Collectors.toSet());

        if (clauseWords.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.fromCallable(documentChunkRepository::findDistinctSourceNames)
                .subscribeOn(Schedulers.boundedElastic())
                .map(sourceNames -> {
                    // Uses the SAME distinctive-word matching as
                    // HybridRetrievalService's whole-document mode - a shared
                    // word like "project" that appears in several filenames
                    // ("company_project_explanation.pdf",
                    // "jpmorgan_chase_project_overview.pdf") doesn't count as
                    // a real match for either; only "flipkart" (appearing in
                    // exactly one filename) does. Without this, "compare this
                    // doc's project with flipkart project" would see BOTH the
                    // JPMorgan file (via "project") and the Flipkart file (via
                    // "flipkart") as candidates, read that as ambiguous, and
                    // resolve to nothing - silently dropping the comparison
                    // target the user explicitly named.
                    Set<String> matched = hybridRetrievalService.findDistinctivelyMatchedSources(clauseWords, sourceNames, Set.of());
                    if (matched.size() == 1) {
                        log.info("Comparison target '{}' matched source: {}", clause.trim(), matched);
                        return matched;
                    }
                    if (matched.size() > 1) {
                        log.info("Comparison target '{}' matched {} sources ({}) - ambiguous, ignoring", clause.trim(), matched.size(), matched);
                    }
                    return Set.<String>of();
                });
    }

    /**
     * True when the question is a pronoun-only follow-up referring back to
     * documents discussed earlier in the session ("these two", "both
     * companies", "what do they have in common") rather than naming a new
     * topic itself. The caller uses this to anchor retrieval to the previous
     * answer's actual sources instead of re-running a fresh, unanchored
     * corpus-wide search.
     */
    public boolean isFollowUpSourceReference(String question) {
        return FOLLOWUP_REFERENCE_PATTERN.matcher(question.toLowerCase()).find();
    }

    /**
     * Lowercased words for matching - splits on non-alphanumeric runs AND on
     * camelCase boundaries (lower/digit -> upper), so a filename like
     * "CoverLetter_Priya.pdf" tokenizes to {cover, letter, priya, pdf} instead
     * of one fused {coverletter, priya, pdf} that can never match the
     * separate words a user actually types ("the cover letter"). Mirrors
     * HybridRetrievalService.wordsOf - kept in sync since both classes match
     * the same set of uploaded filenames against free-text fragments. Must
     * split camelCase BEFORE lowercasing - the case information marking the
     * boundary is gone once everything is already lowercase.
     */
    private Set<String> wordsOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String token : text.split("[^a-zA-Z0-9]+")) {
            if (token.isBlank()) continue;
            String spaced = token.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
            for (String w : spaced.toLowerCase().split("\\s+")) {
                if (!w.isBlank()) words.add(w);
            }
        }
        return words;
    }
}
