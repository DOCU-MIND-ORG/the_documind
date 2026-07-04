package com.accenture.intern.docmind.dto.job;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EpisodicSummaryResult {
    private String topic;
    private String summary;
    private List<String> entities;
    private List<String> keywords;
    private Double importanceScore;
}
