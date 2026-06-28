package com.accenture.intern.docmind.dto.chat;

import java.util.ArrayList;
import java.util.List;

import com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation;

public class RetrievalTrace {
    private List<String> steps = new ArrayList<>();
    private List<RetrievalObservation> observations = new ArrayList<>();

    public void addStep(String step) {
        steps.add(step);
    }
    
    public void addObservation(RetrievalObservation obs) {
        observations.add(obs);
    }

    public List<String> getSteps() {
        return steps;
    }
    
    public List<RetrievalObservation> getObservations() {
        return observations;
    }

    @Override
    public String toString() {
        return "RetrievalTrace{" +
                "steps=" + steps +
                '}';
    }
}
