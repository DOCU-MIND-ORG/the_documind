package com.accenture.intern.docmind.aiservices.vision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
public class ImageVisionService {

    private static final String VISION_PROMPT = """
            You are an expert document intelligence agent indexing an enterprise document for semantic retrieval.
            Analyze the provided image asset. If surrounding context is provided, use it to accurately interpret the image.
            Return a valid JSON object matching this schema exactly:

            {
              "summary": "A detailed, descriptive summary of what this image shows.",
              "imageType": "CHART | FLOWCHART | INFOGRAPHIC | SCREENSHOT | LOGO | DIAGRAM | ARCHITECTURE | PHOTO",
              "entities": ["Named entities, people, or specific companies found in the image"],
              "topics": ["High-level concepts or topics the image relates to"],
              "objects": ["Key components or general objects identified in the image"],
              "technologies": ["Any technical systems, tools, or software mentioned/shown"],
              "relationships": ["Descriptions of how elements in the image connect (e.g. 'A sends data to B')"],
              "ocr": "All raw readable text found in the image",
              "keywords": ["5-10 high-value keywords optimized for semantic search"]
            }
            """;

    private final ChatClient chatClient;

    public ImageVisionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public Mono<SemanticImage> describeImage(byte[] imageBytes, String mimeType, String contextText) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    try {
                        MimeType type = MimeType.valueOf(
                                (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType);

                        String promptText = VISION_PROMPT;
                        if (contextText != null && !contextText.isBlank()) {
                            promptText += "\n\n=== SURROUNDING CONTEXT ===\n" + contextText;
                        }

                        final String finalPromptText = promptText;

                        SemanticImage parsedImage = chatClient.prompt()
                                .user(u -> u.text(finalPromptText)
                                        .media(type, new ByteArrayResource(imageBytes)))
                                .call()
                                .entity(SemanticImage.class);

                        if (parsedImage == null) return null;

                        return new SemanticImage(
                                parsedImage.summary(),
                                parsedImage.keywords(),
                                parsedImage.entities(),
                                parsedImage.topics(),
                                parsedImage.technologies(),
                                parsedImage.objects(),
                                parsedImage.relationships(),
                                parsedImage.ocr(),
                                parsedImage.imageType(),
                                contextText
                        );
                    } catch (Exception e) {
                        log.error("Gemini Vision failed to describe image: {}", e.getMessage(), e);
                        return null;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
