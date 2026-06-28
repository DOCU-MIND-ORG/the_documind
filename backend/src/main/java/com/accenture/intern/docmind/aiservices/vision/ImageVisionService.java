package com.accenture.intern.docmind.aiservices.vision;

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
            You are an expert document intelligence agent. Analyze the provided image asset and return a valid JSON object matching this schema exactly:

            {
              "suggested_filename": "A lowercase, snake_case descriptive name ending with the original extension (e.g., quarterly_revenue_bar_chart.png). Avoid generic stamps.",
              "asset_classification": "CHART | FLOWCHART | INFOGRAPHIC | SCREENSHOT | DOCUMENT_PAGE | UI_MOCKUP",
              "tags": ["3-5 high-value standalone keywords found inside the image"],
              "dense_description": "The complete, granular text description of the image asset. If it's a chart, list axes, values, and trends. If a diagram, detail every node and connection. Do not truncate text."
            }
            """;

    private final ChatClient chatClient;

    public ImageVisionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Describes a single image. Never throws — on any failure it logs and returns
     * null so one bad image can't fail an entire document's ingestion.
     *
     * @param imageBytes raw image bytes (png/jpeg/webp/etc.)
     * @param mimeType   e.g. "image/png", "image/jpeg"; defaults to image/png if blank
     */
    public Mono<ImageVisionResponse> describeImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    try {
                        MimeType type = MimeType.valueOf(
                                (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType);

                        return chatClient.prompt()
                                .user(u -> u.text(VISION_PROMPT)
                                        .media(type, new ByteArrayResource(imageBytes)))
                                .call()
                                .entity(ImageVisionResponse.class);
                    } catch (Exception e) {
                        log.error("Gemini Vision failed to describe image: {}", e.getMessage(), e);
                        return null;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

