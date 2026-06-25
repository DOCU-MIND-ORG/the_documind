package com.accenture.intern.docmind.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Wraps all server-side (signed) Cloudinary operations: uploading profile
 * pictures, PDFs, and document images, plus deleting them. Replaces local
 * disk storage for these asset types - everything is keyed by Cloudinary's
 * {@code public_id} (used for deletes) and {@code secure_url} (the URL we
 * actually persist/serve, e.g. in Attachment.url or citation imageUrl).
 */
@Slf4j
@Service
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public CloudinaryService(@Value("${CLOUDINARY_URL:}") String cloudinaryUrl) {
        if (cloudinaryUrl != null && !cloudinaryUrl.isEmpty()) {
            this.cloudinary = new Cloudinary(cloudinaryUrl);
        } else {
            // Provide a fallback or default initialization if env is missing
            this.cloudinary = new Cloudinary(ObjectUtils.emptyMap());
        }
    }

    /** Result of a successful Cloudinary upload - what callers need to persist. */
    public record UploadResult(String url, String publicId) {}

    /**
     * Uploads image bytes (profile pictures, directly-uploaded images, images
     * extracted from inside a PDF) to Cloudinary as resource_type "image"
     * under the given folder, e.g. "assets/profile_images" or
     * "storage/images/extracted". A random UUID is used as the public_id
     * filename so uploads never collide.
     */
    public UploadResult uploadImage(byte[] bytes, String folder, String originalFileName) {
        try {
            String publicId = buildPublicId(originalFileName, false);
            Map<String, Object> options = ObjectUtils.asMap(
                    "folder", folder,
                    "public_id", publicId,
                    "resource_type", "image",
                    "overwrite", true
            );
            Map result = cloudinary.uploader().upload(bytes, options);
            return new UploadResult((String) result.get("secure_url"), (String) result.get("public_id"));
        } catch (Exception e) {
            log.error("Failed to upload image to Cloudinary (folder={}): {}", folder, e.getMessage(), e);
            throw new RuntimeException("Failed to upload image to Cloudinary: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads arbitrary bytes (PDFs) to Cloudinary as resource_type "raw"
     * under the given folder, e.g. "storage/pdfs". Raw is used (rather than
     * "image") since we only need to store/retrieve the original file, not
     * render PDF-page previews.
     * <p>
     * Unlike image/video, Cloudinary requires a raw asset's public_id to
     * include the file extension - otherwise the delivered secure_url has
     * no extension and browsers/viewers can't tell what kind of file it is.
     * The publicId returned here (and stored on Attachment for later
     * deletion) already includes that extension.
     * <p>
     * access_mode "public" is set explicitly so the file is servable as
     * soon as it's uploaded. Note this does NOT bypass Cloudinary's
     * account-level "PDF and ZIP files delivery" security toggle - that
     * still has to be enabled once in the Cloudinary console (Settings >
     * Security), or even a public, correctly-uploaded PDF will 401 when
     * fetched.
     */
    public UploadResult uploadRaw(byte[] bytes, String folder, String originalFileName) {
        try {
            String publicId = buildPublicId(originalFileName, true);
            Map<String, Object> options = ObjectUtils.asMap(
                    "folder", folder,
                    "public_id", publicId,
                    "resource_type", "raw",
                    "access_mode", "public",
                    "overwrite", true
            );
            Map result = cloudinary.uploader().upload(bytes, options);
            return new UploadResult((String) result.get("secure_url"), (String) result.get("public_id"));
        } catch (Exception e) {
            log.error("Failed to upload raw file to Cloudinary (folder={}): {}", folder, e.getMessage(), e);
            throw new RuntimeException("Failed to upload file to Cloudinary: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a previously-uploaded asset. resourceType must match what it
     * was uploaded as ("image" or "raw") - Cloudinary's destroy API scopes
     * lookups by resource_type, so passing the wrong one silently no-ops
     * instead of deleting anything.
     */
    public void deleteAsset(String publicId, String resourceType) {
        if (publicId == null || publicId.isEmpty()) return;

        try {
            Map result = cloudinary.uploader().destroy(publicId,
                    ObjectUtils.asMap("resource_type", resourceType == null ? "image" : resourceType));
            log.info("Cloudinary delete result for {} ({}): {}", publicId, resourceType, result);
        } catch (Exception e) {
            log.error("Failed to delete asset from Cloudinary: {}", e.getMessage(), e);
        }
    }

    /** Backward-compatible alias - existing callers delete profile images (resource_type "image"). */
    public void deleteImage(String publicId) {
        deleteAsset(publicId, "image");
    }

    /**
     * Builds a collision-free public_id from the original filename.
     * For image/video uploads, Cloudinary stores the extension separately
     * (the "format" field) so it should NOT be part of the public_id. For
     * raw uploads (PDFs), Cloudinary requires the extension to be part of
     * the public_id itself, or the delivered URL ends up extensionless.
     */
    private String buildPublicId(String originalFileName, boolean keepExtension) {
        String base = originalFileName == null ? "file" : originalFileName;
        int dot = base.lastIndexOf('.');
        String extension = (dot > 0) ? base.substring(dot) : ""; // includes the leading "."
        String name = (dot > 0) ? base.substring(0, dot) : base;
        name = name.replaceAll("[^a-zA-Z0-9_-]", "_");

        String safeExtension = keepExtension ? extension.replaceAll("[^a-zA-Z0-9.]", "") : "";
        return UUID.randomUUID() + "_" + name + safeExtension;
    }
}
