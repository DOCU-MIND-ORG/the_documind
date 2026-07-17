package com.accenture.intern.docmind.entity;

public enum MessageStatus {
    STREAMING, // Legacy
    COMPLETE,  // Legacy
    ERROR,     // Legacy
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_FOR_DOCUMENTS,
    FAILED_TIMEOUT
}
