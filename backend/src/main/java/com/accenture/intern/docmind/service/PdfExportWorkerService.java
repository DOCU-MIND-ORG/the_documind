package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.aiservices.context.SessionSummaryService;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Background worker for PDF session exports.
 * <p>
 * Per the TODO left in SessionController, PDF export is too slow/resource-
 * intensive (an LLM summarization call + PDF rendering) to run inline on a
 * request thread or even as a simple {@code @Async} task — that would tie up
 * web server capacity under load from many concurrent users. Instead,
 * requests are queued onto the {@code pdf_export_jobs} Redis stream (see
 * SessionService#requestPdfExport) and this worker drains that stream on a
 * schedule, exactly like {@link com.accenture.intern.docmind.aiservices.chat.LlmWorkerService}
 * does for chat generation. This lets the work be picked up by any worker
 * node in a multi-instance deployment, and a slow export never blocks chat
 * traffic or any other endpoint.
 * <p>
 * The rendered PDF is stored as base64 bytes directly in a short-lived Redis
 * key (no Cloudinary involved — an export is a one-time download to the
 * user's own machine, not part of the shared document corpus, so there is no
 * reason to keep it in cloud storage). SessionController exposes a download
 * endpoint that streams these bytes back with a Content-Disposition header
 * once the frontend sees status == READY.
 */
@Slf4j
@Service
public class PdfExportWorkerService {

    private static final String STREAM_KEY = "pdf_export_jobs";
    private static final String CONSUMER_GROUP = "pdf-export-workers";
    private final String consumerName = "pdf-worker-" + java.util.UUID.randomUUID().toString();
    private volatile boolean running = true;
    private final java.util.concurrent.ExecutorService workerExecutor;
    private static final String RESULT_KEY_PREFIX = "pdf_export_result:";
    private static final String BLOB_KEY_PREFIX = "pdf_export_blob:";
    private static final Duration RESULT_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final SessionSummaryService sessionSummaryService;
    private final PdfGeneratorService pdfGeneratorService;
    private final ObjectMapper objectMapper;

    private StreamOperations<String, Object, Object> streamOps;

    private final com.accenture.intern.docmind.service.AnalyticsService analyticsService;
    private final TransactionTemplate transactionTemplate;

    public PdfExportWorkerService(RedisTemplate<String, Object> redisTemplate,
                                   SessionRepository sessionRepository,
                                   MessageRepository messageRepository,
                                   SessionSummaryService sessionSummaryService,
                                   PdfGeneratorService pdfGeneratorService,
                                   ObjectMapper objectMapper,
                                   java.util.concurrent.ExecutorService workerExecutor,
                                   com.accenture.intern.docmind.service.AnalyticsService analyticsService,
                                   TransactionTemplate transactionTemplate) {
        this.redisTemplate = redisTemplate;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionSummaryService = sessionSummaryService;
        this.pdfGeneratorService = pdfGeneratorService;
        this.objectMapper = objectMapper;
        this.workerExecutor = workerExecutor;
        this.analyticsService = analyticsService;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        streamOps = redisTemplate.opsForStream();
        try {
            streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("PDF export consumer group likely exists already");
        }
        workerExecutor.submit(this::runWorker);
    }

    @jakarta.annotation.PreDestroy
    public void destroy() {
        running = false;
    }

    public void runWorker() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().block(Duration.ofSeconds(30)).count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    processJob(record);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(1000); // Backoff slightly to avoid tight loop
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.error("Error polling jobs from Redis stream {}", STREAM_KEY, e);
                }
            }
        }
    }

    private void processJob(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        String jobId = (String) value.get("jobId");
        Long sessionId = Long.parseLong((String) value.get("sessionId"));
        String userEmail = (String) value.get("userEmail");

        String lockKey = "lock:pdf-export:" + jobId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(acquired)) {
            log.info("PDF export job {} already being processed", jobId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    writeStatus(jobId, "PROCESSING", null, null);
                    generatePdf(jobId, sessionId, userEmail);
                    analyticsService.incrementPdfExports();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process PDF export job {}", jobId, e);
            writeStatus(jobId, "FAILED", null, "Failed to generate PDF: " + e.getMessage());
        }
    }

    private void generatePdf(String jobId, Long sessionId, String userEmail) throws Exception {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUser().getEmail().equals(userEmail)) {
            throw new RuntimeException("Access denied");
        }

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(session);

        // Summarization happens here, on the worker thread — never inline on
        // the original HTTP request — since it's an extra LLM round trip.
        String summary = sessionSummaryService.summarize(messages).block();

        byte[] pdfBytes = pdfGeneratorService.generateSessionPdf(session, summary, messages);

        String safeTitle = (session.getTitle() != null && !session.getTitle().isBlank() ? session.getTitle() : "session")
                .replaceAll("[^a-zA-Z0-9-_ ]", "")
                .trim()
                .replaceAll("\\s+", "-");
        String fileName = (safeTitle.isBlank() ? "session-" + sessionId : safeTitle) + ".pdf";

        // Store the rendered PDF directly in Redis (base64) rather than
        // uploading anywhere — an export is a one-off download to the user's
        // own machine, not part of the shared document corpus, so there's no
        // reason to retain a copy in cloud storage. SessionController's
        // download endpoint reads this same key and streams it back with a
        // Content-Disposition: attachment header.
        String base64 = java.util.Base64.getEncoder().encodeToString(pdfBytes);
        redisTemplate.opsForValue().set(BLOB_KEY_PREFIX + jobId, base64, RESULT_TTL);

        writeStatus(jobId, "READY", fileName, null);
        log.info("PDF export job {} complete for session {} ({} bytes)", jobId, sessionId, pdfBytes.length);
    }

    private void writeStatus(String jobId, String status, String fileName, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        if (fileName != null) payload.put("fileName", fileName);
        if (errorMessage != null) payload.put("errorMessage", errorMessage);

        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(RESULT_KEY_PREFIX + jobId, json, RESULT_TTL);
        } catch (Exception e) {
            log.error("Failed to persist PDF export status for job {}", jobId, e);
        }
    }
}
