package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.preference.ModelInfoResponse;
import com.accenture.intern.docmind.dto.preference.PreferenceResponse;
import com.accenture.intern.docmind.dto.preference.UpdatePreferenceRequest;
import com.accenture.intern.docmind.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Handles user preference endpoints.
 */
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;

    // GET /api/preferences
    @GetMapping
    public ResponseEntity<PreferenceResponse> getPreferences(Principal principal) {
        PreferenceResponse response = preferenceService.getPreferences(principal.getName());
        return ResponseEntity.ok(response);
    }

    // PUT /api/preferences
    @PutMapping
    public ResponseEntity<PreferenceResponse> updatePreferences(
            @RequestBody UpdatePreferenceRequest request,
            Principal principal
    ) {
        PreferenceResponse response = preferenceService.updatePreferences(principal.getName(), request);
        return ResponseEntity.ok(response);
    }

    // PUT /api/preferences/model
    @PutMapping("/model")
    public ResponseEntity<PreferenceResponse> updateModel(
            @RequestBody com.accenture.intern.docmind.dto.preference.UpdateModelRequest request,
            Principal principal
    ) {
        UpdatePreferenceRequest updateRequest = new UpdatePreferenceRequest();
        updateRequest.setModelName(request.getModelName());
        updateRequest.setTheme(request.getTheme());
        updateRequest.setResponseStyle(request.getResponseStyle());
        updateRequest.setLanguage(request.getLanguage());
        PreferenceResponse response = preferenceService.updatePreferences(principal.getName(), updateRequest);
        return ResponseEntity.ok(response);
    }

    // GET /api/preferences/models
    @GetMapping("/models")
    public ResponseEntity<List<ModelInfoResponse>> getAvailableModels() {
        List<ModelInfoResponse> models = preferenceService.getAvailableModels();
        return ResponseEntity.ok(models);
    }
}