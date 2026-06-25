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
        // Nothing is deleted from Cloudinary when an Attachment row is removed.
        // Both PDF and IMAGE attachments (and images extracted from inside a PDF,
        // which aren't even Attachment rows - they only exist as metadata on
        // retrieved chunks) are deliberately left in place: a citation can keep
        // referencing a chunk's sourceUrl (PDF) or imageUrl (image) long after the
        // session/attachment that originally uploaded it is deleted, so deleting
        // the Cloudinary asset here would silently break those citation cards/PDF
        // links. Cloudinary cleanup, if ever needed, should be its own explicit,
        // intentional flow - never a side effect of session/attachment cleanup.

        // Locally-stored attachments (TEXT, OTHER, or any legacy rows from before the Cloudinary migration)
        if (attachment.getStoragePath() != null) {
            try {
                Path path = Paths.get(storageRoot, attachment.getStoragePath()).toAbsolutePath().normalize();
                if (Files.exists(path)) {
                    Files.delete(path);
                    log.info("Deleted attachment file from disk: {}", path);
                }
            } catch (Exception e) {
                log.error("Failed to delete attachment file from disk: {}", attachment.getStoragePath(), e);
            }
        }
    }
}
