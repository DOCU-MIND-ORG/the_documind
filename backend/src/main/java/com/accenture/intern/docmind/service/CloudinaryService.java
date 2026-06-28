package com.accenture.intern.docmind.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.io.IOException;

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

    public void deleteImage(String publicId) {
        if (publicId == null || publicId.isEmpty()) return;
        
        try {
            // Delete image from Cloudinary
            Map result = cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            System.out.println("Cloudinary delete result for " + publicId + ": " + result);
        } catch (Exception e) {
            System.err.println("Failed to delete image from Cloudinary: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public record UploadResult(String url, String publicId) {}

    public UploadResult uploadImage(byte[] fileBytes, String folder, String originalName) throws IOException {
        String publicId = UUID.randomUUID().toString();
        Map params = ObjectUtils.asMap(
                "folder", folder,
                "public_id", publicId,
                "resource_type", "image"
        );
        Map uploadResult = cloudinary.uploader().upload(fileBytes, params);
        return new UploadResult((String) uploadResult.get("secure_url"), (String) uploadResult.get("public_id"));
    }

    public UploadResult uploadRaw(byte[] fileBytes, String folder, String originalName) throws IOException {
        String publicId = UUID.randomUUID().toString() + "_" + originalName;
        Map params = ObjectUtils.asMap(
                "folder", folder,
                "public_id", publicId,
                "resource_type", "raw"
        );
        Map uploadResult = cloudinary.uploader().upload(fileBytes, params);
        return new UploadResult((String) uploadResult.get("secure_url"), (String) uploadResult.get("public_id"));
    }
}
