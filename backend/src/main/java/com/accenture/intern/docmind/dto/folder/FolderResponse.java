package com.accenture.intern.docmind.dto.folder;

import com.accenture.intern.docmind.dto.session.SessionResponse;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderResponse {
    private Long id;
    private String name;
    private String icon;
    private String colorHex;
    private Integer displayOrder;
    private Boolean pinned;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SessionResponse> sessions;
}
