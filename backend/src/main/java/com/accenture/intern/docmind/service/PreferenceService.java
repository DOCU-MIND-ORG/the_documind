package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.preference.ModelInfoResponse;
import com.accenture.intern.docmind.dto.preference.PreferenceResponse;
import com.accenture.intern.docmind.dto.preference.UpdatePreferenceRequest;
import com.accenture.intern.docmind.entity.ModelName;
import com.accenture.intern.docmind.entity.ResponseStyle;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.entity.UserPreference;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing user preferences.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;
    private final UserRepository userRepository;

    public PreferenceResponse getPreferences(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new RuntimeException("User not found 🚫");
        }

        UserPreference preference = userPreferenceRepository.findByUser(user)
                .orElseGet(() -> {
                    UserPreference defaultPref = UserPreference.builder()
                            .user(user)
                            .theme("dark")
                            .language("en")
                            .citationEnabled(true)
                            .responseStyle(ResponseStyle.BEGINNER)
                            .modelName(ModelName.GEMINI_3_1_FLASH_LITE)
                            .temperature(0.7)
                            .build();
                    return userPreferenceRepository.save(defaultPref);
                });

        return mapToResponse(preference);
    }

    public PreferenceResponse updatePreferences(String email, UpdatePreferenceRequest request) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new RuntimeException("User not found 🚫");
        }

        UserPreference preference = userPreferenceRepository.findByUser(user)
                .orElseGet(() -> UserPreference.builder().user(user).build());

        if (request.getTheme() != null) {
            preference.setTheme(request.getTheme());
        }
        if (request.getLanguage() != null) {
            preference.setLanguage(request.getLanguage());
        }
        if (request.getCitationEnabled() != null) {
            preference.setCitationEnabled(request.getCitationEnabled());
        }
        if (request.getResponseStyle() != null) {
            try {
                preference.setResponseStyle(ResponseStyle.valueOf(request.getResponseStyle().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Ignore or handle invalid enum value
            }
        }
        if (request.getModelName() != null) {
            try {
                preference.setModelName(ModelName.valueOf(request.getModelName().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Ignore or handle invalid enum value
            }
        }
        if (request.getTemperature() != null) {
            preference.setTemperature(request.getTemperature());
        }

        UserPreference saved = userPreferenceRepository.save(preference);
        return mapToResponse(saved);
    }

    public List<ModelInfoResponse> getAvailableModels() {
        return Arrays.stream(ModelName.values())
                .map(model -> ModelInfoResponse.builder()
                        .id(model.name())
                        .name(model.getDisplayName())
                        .description(model.getDescription())
                        .isNew(model.isNew())
                        .build())
                .collect(Collectors.toList());
    }

    private PreferenceResponse mapToResponse(UserPreference preference) {
        return PreferenceResponse.builder()
                .theme(preference.getTheme())
                .language(preference.getLanguage())
                .citationEnabled(preference.getCitationEnabled())
                .responseStyle(preference.getResponseStyle() != null ? preference.getResponseStyle().name() : null)
                .modelName(preference.getModelName() != null ? preference.getModelName().name() : null)
                .temperature(preference.getTemperature())
                .build();
    }
}