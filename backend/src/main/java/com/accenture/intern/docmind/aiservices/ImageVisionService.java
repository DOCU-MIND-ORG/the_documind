package com.accenture.intern.docmind.aiservices;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns an image (a standalone IMAGE attachment, or an image/chart/graph extracted
 * from inside a PDF) into a detailed text description using Gemini's multimodal
 * ("vision") capability via Spring AI's {@link ChatClient}.
 * <p>
 * DocMind does not store separate image-embedding vectors. Instead, the
 * description produced here is treated exactly like any other piece of document
 * text: it gets handed to {@link EmbeddingService#processAndIngest}, chunked,
 * embedded, and upserted into Pinecone/Postgres alongside normal text chunks. This
 * lets a user ask "what does the revenue chart on page 3 show?" and have it
 * surface through the same hybrid retrieval path as everything else.
 */
@Slf4j
@Service
public class ImageVisionService {

    private static final String VISION_PROMPT = """
            You are converting an image into a precise text description that will be
            stored in a search index in place of the image. The description must let
            someone answer questions about the image without ever seeing it.

            - If it is a chart, graph, or plot: state the chart type, axis labels and
              units, every series/legend entry, the approximate data values or
              trend for each series, and the main takeaway or insight.
            - If it is a table rendered as an image: transcribe it as text, row by
              row, preserving column headers and values.
            - If it is a diagram or flowchart: describe each node/step and how they
              connect, in order.
            - If it is a photo, illustration, screenshot, or logo: describe the
              subject, setting, any visible text, and relevant details.

            Be specific and factual. Do not say things like "an image of something".
            Respond with plain text only, no markdown formatting, no preamble.
            """;

    private final ChatClient chatClient;

    public ImageVisionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Describes a single image. Never throws — on any failure it logs and returns
     * an empty string so one bad image can't fail an entire document's ingestion.
     *
     * @param imageBytes raw image bytes (png/jpeg/webp/etc.)
     * @param mimeType   e.g. "image/png", "image/jpeg"; defaults to image/png if blank
     */
    public Mono<String> describeImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.just("");
        }

        return Mono.fromCallable(() -> {
                    try {
                        MimeType type = MimeType.valueOf(
                                (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType);

                        String description = chatClient.prompt()
                                .user(u -> u.text(VISION_PROMPT)
                                        .media(type, new ByteArrayResource(imageBytes)))
                                .call()
                                .content();

                        return description == null ? "" : description.trim();
                    } catch (Exception e) {
                        log.error("Gemini Vision failed to describe image: {}", e.getMessage(), e);
                        return "";
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
