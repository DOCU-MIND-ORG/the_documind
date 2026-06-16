package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.preference.PreferenceResponse;
import com.accenture.intern.docmind.dto.preference.UpdatePreferenceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles user preference endpoints.
 * Implementation to be completed by the assigned developer.
 */
@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

    // GET /api/preferences
    @GetMapping
    public ResponseEntity<PreferenceResponse> getPreferences() {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    // PATCH /api/preferences
    @PatchMapping
    public ResponseEntity<PreferenceResponse> updatePreferences(@RequestBody UpdatePreferenceRequest request) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
