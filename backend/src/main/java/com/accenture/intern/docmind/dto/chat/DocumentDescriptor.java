package com.accenture.intern.docmind.dto.chat;

import java.util.List;

public record DocumentDescriptor(
        String canonical,
        List<String> aliases,
        String domain,
        String type
) {
}
