package com.accenture.intern.docmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "docmind.retrieval")
public class RetrievalProperties {

    private double primaryThreshold = 0.42;
    private double sessionThreshold = 0.35;

    public double getPrimaryThreshold() {
        return primaryThreshold;
    }

    public void setPrimaryThreshold(double primaryThreshold) {
        this.primaryThreshold = primaryThreshold;
    }

    public double getSessionThreshold() {
        return sessionThreshold;
    }

    public void setSessionThreshold(double sessionThreshold) {
        this.sessionThreshold = sessionThreshold;
    }
}
