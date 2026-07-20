package com.accenture.intern.docmind.controller;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final WebClient webClient;
    
    public VoiceController(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8000").build();
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map>> transcribeAudio(@RequestPart("audio") FilePart audio) {
        return DataBufferUtils.join(audio.content())
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);

                ByteArrayResource resource = new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return audio.filename() != null ? audio.filename() : "audio.webm";
                    }
                };

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("audio", resource);

                return webClient.post()
                        .uri("/transcribe")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(body)
                        .retrieve()
                        .toEntity(Map.class);
            })
            .onErrorResume(e -> {
                e.printStackTrace();
                return Mono.just(ResponseEntity.status(500).body(Map.of("error", "Failed to transcribe audio: " + e.getMessage())));
            });
    }
}
