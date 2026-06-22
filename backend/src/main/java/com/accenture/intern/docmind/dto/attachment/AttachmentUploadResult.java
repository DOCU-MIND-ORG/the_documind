package com.accenture.intern.docmind.dto.attachment;

import reactor.core.publisher.Mono;

public record AttachmentUploadResult(AttachmentResponse response, Mono<Void> ingestionMono) {}
