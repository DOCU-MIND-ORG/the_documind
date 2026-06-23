package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.entity.UserPreference;
import com.accenture.intern.docmind.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelFactory {

    private final UserPreferenceRepository userPreferenceRepository;

    public GoogleGenAiChatOptions getChatOptions(User user, String overrideModelName) {
        UserPreference pref = userPreferenceRepository.findByUser(user)
                .orElse(null);

        GoogleGenAiChatOptions options = new GoogleGenAiChatOptions();
        
        if (overrideModelName != null && !overrideModelName.trim().isEmpty()) {
            try {
                com.accenture.intern.docmind.entity.ModelName overrideModel = com.accenture.intern.docmind.entity.ModelName.valueOf(overrideModelName);
                options.setModel(overrideModel.getModelString());
            } catch (IllegalArgumentException e) {
                // If the frontend sends an invalid model name, fallback to preferences
                if (pref != null && pref.getModelName() != null) {
                    options.setModel(pref.getModelName().getModelString());
                }
            }
        } else if (pref != null && pref.getModelName() != null) {
            options.setModel(pref.getModelName().getModelString());
        }

        if (pref != null && pref.getTemperature() != null) {
            options.setTemperature(pref.getTemperature());
        }
        return options;
    }

    public String injectResponseStyle(User user, String basePrompt) {
        UserPreference pref = userPreferenceRepository.findByUser(user)
                .orElse(null);

        if (pref != null && pref.getResponseStyle() != null) {
            String styleInstruction = switch (pref.getResponseStyle()) {
                case TECHNICAL -> "Provide a highly technical, precise, and professional response, using appropriate terminology.";
                case CONCISE -> "Provide a brief, direct, and concise response without fluff or unnecessary details.";
                case DETAILED -> "Provide an in-depth, thorough, and highly detailed response, exploring all relevant aspects.";
                case BEGINNER -> "Explain the concepts simply, as if speaking to a beginner, avoiding overly complex jargon.";
            };
            return basePrompt + "\n\nCRITICAL INSTRUCTION: " + styleInstruction;
        }
        return basePrompt;
    }
}
