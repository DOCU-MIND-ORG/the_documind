package com.accenture.intern.docmind.dto.session;

import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Response for GET /api/sessions/{id}/suggested-questions.
 * The frontend polls this after an upload until status is READY or FAILED.
 */
@Getter
@Builder
@AllArgsConstructor
public class SuggestedQuestionsResponse {
    private SessionUploadState.SuggestedQuestionsStatus status;
    private List<String> questions;
}
