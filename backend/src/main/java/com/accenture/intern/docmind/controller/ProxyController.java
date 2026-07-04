package com.accenture.intern.docmind.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final WebClient webClient = WebClient.builder()
            .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                    .build())
            .build();

    @GetMapping
    public Mono<ResponseEntity<byte[]>> proxyFile(@RequestParam("url") String url) {
        if (url == null || url.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        java.net.URI uri;
        try {
            // If the URL is already properly encoded (e.g. contains %20), this will succeed.
            uri = new java.net.URI(url);
        } catch (Exception e) {
            try {
                // If it contains raw spaces or unencoded chars, this will encode them.
                java.net.URL parsedUrl = new java.net.URL(url);
                uri = new java.net.URI(parsedUrl.getProtocol(), parsedUrl.getUserInfo(), parsedUrl.getHost(), parsedUrl.getPort(), parsedUrl.getPath(), parsedUrl.getQuery(), parsedUrl.getRef());
            } catch (Exception ex) {
                System.err.println("Failed to parse URL for proxy: " + url);
                return Mono.just(ResponseEntity.badRequest().build());
            }
        }

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> {
                    MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
                    if (url.toLowerCase().endsWith(".pdf")) {
                        mediaType = MediaType.APPLICATION_PDF;
                    } else if (url.toLowerCase().endsWith(".png")) {
                        mediaType = MediaType.IMAGE_PNG;
                    } else if (url.toLowerCase().endsWith(".jpg") || url.toLowerCase().endsWith(".jpeg")) {
                        mediaType = MediaType.IMAGE_JPEG;
                    }

                    return ResponseEntity.ok()
                            .contentType(mediaType)
                            .body(bytes);
                })
                .onErrorResume(e -> {
                    System.err.println("PROXY ERROR for URL " + url + ": " + e.getMessage());
                    e.printStackTrace();
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
}
