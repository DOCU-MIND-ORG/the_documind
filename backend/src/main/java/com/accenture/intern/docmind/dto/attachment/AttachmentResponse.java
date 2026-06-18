package com.accenture.intern.docmind.dto.attachment;

import com.accenture.intern.docmind.entity.AttachmentType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AttachmentResponse {
    private Long attachmentId;
    private Long messageId;
    private AttachmentType type;
    private String fileName;
    private String storagePath;
    private String url;
    private String mimeType;
    private Long fileSizeBytes;
    private LocalDateTime uploadedAt;
}
