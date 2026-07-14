package com.accenture.intern.docmind.aiservices.understanding;

public enum RetrievalExecutionMode {
    WHOLE_DOCUMENT,
    RANKED_RETRIEVAL,
    CONTIGUOUS   // anchor chunk → sectionPath expansion; preserves sequential reading order of one logical section
}

