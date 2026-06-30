package com.accenture.intern.docmind.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "analytics_daily")
@Getter
@Setter
public class AnalyticsDaily {
    
    @Id
    private LocalDate date;

    private long uniqueVisitors;
    private long chatRequests;
    private long documentsUploaded;
    private long pdfExports;
    private long tokensGenerated;
    private double avgResponseMs;
    private long responseCount; // Used to recalculate the average
}
