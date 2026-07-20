package com.accenture.intern.docmind.dto.folder;

import lombok.Data;

@Data
public class ReorderRequest {
    private Long id;
    private Integer order;
}
