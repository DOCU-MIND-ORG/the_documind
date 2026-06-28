package com.accenture.intern.docmind.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.persistence.PreRemove;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class AttachmentEntityListener {

    private static final Logger log = LoggerFactory.getLogger(AttachmentEntityListener.class);

    @Value("${app.storage.root:storage}")
    private String storageRoot;

    @PreRemove
    public void preRemove(Attachment attachment) {
        // The user specifically requested to keep the physical file on disk even when the
        // attachment row is deleted from the database.
        
        // if (attachment.getStoragePath() != null) {
        //     try {
        //         Path path = Paths.get(storageRoot, attachment.getStoragePath()).toAbsolutePath().normalize();
        //         if (Files.exists(path)) {
        //             Files.delete(path);
        //             log.info("Deleted attachment file from disk: {}", path);
        //         }
        //     } catch (Exception e) {
        //         log.error("Failed to delete attachment file from disk: {}", attachment.getStoragePath(), e);
        //     }
        // }
    }
}
