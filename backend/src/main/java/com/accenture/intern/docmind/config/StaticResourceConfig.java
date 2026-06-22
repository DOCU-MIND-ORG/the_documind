package com.accenture.intern.docmind.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.nio.file.Paths;

/**
 * Serves files from the local storage directory under the /files/** URL path.
 *
 * Example:
 *   File on disk: backend/storage/pdfs/uuid_report.pdf
 *   Served at:    GET /files/pdfs/uuid_report.pdf
 */
@Configuration
public class StaticResourceConfig implements WebFluxConfigurer {

    @Value("${app.storage.root:storage}")
    private String storageRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Resolve to an absolute file:// URI so Spring can serve from any working directory
        String absolutePath = Paths.get(storageRoot).toAbsolutePath().normalize().toUri().toString();
        if (!absolutePath.endsWith("/")) absolutePath += "/";

        registry.addResourceHandler("/files/**")
                .addResourceLocations(absolutePath);
    }
}
