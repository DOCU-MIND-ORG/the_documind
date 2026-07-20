package com.accenture.intern.docmind.dto.folder;

import lombok.Data;
import java.util.List;

@Data
public class CreateFolderRequest {
    private String name;
    private String icon;
    private String colorHex;
    private List<Long> sessionIds;
}
