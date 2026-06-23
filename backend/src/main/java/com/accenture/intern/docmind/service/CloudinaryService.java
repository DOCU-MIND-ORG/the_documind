package com.accenture.intern.docmind.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

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
}
