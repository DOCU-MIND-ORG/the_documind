package com.accenture.intern.docmind.dto.context;

public record DocumentReference(
    String filename,
    Long uploadTimestamp
) {
}
