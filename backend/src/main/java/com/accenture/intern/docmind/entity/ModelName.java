package com.accenture.intern.docmind.entity;

public enum ModelName {
    GEMINI_2_5_FLASH("Gemini 2.5 Flash", "All-around help", true),
    GEMINI_2_5_FLASH_LITE("Gemini 2.5 Flash Lite", "Fastest answers", true),
    GEMINI_2_5_PRO("Gemini 2.5 Pro", "Advanced maths and code", false),
    GEMINI_2_5_PRO_LITE("Gemini 2.5 Pro Lite", "Complex reasoning with fast response", false),
    GEMINI_3_1_FLASH_LITE("Gemini 3.1 Flash Lite","Best Balanced",false);

    private final String displayName;
    private final String description;
    private final boolean isNew;

    ModelName(String displayName, String description, boolean isNew) {
        this.displayName = displayName;
        this.description = description;
        this.isNew = isNew;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isNew() {
        return isNew;
    }

    public String getModelString() {
        return this.name().toLowerCase().replace("_", "-").replace("-2-5-", "-2.5-").replace("-3-1-","-3.1-");
    }
}