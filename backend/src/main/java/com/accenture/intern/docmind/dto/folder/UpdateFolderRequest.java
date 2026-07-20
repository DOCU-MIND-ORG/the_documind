package com.accenture.intern.docmind.dto.folder;

import lombok.Data;

@Data
public class UpdateFolderRequest {
    private String name;
    private String icon;
    private String colorHex;
    private Boolean pinned;
}
