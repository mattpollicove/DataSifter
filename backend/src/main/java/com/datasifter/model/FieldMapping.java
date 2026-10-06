package com.datasifter.model;

import java.util.ArrayList;
import java.util.List;

public class FieldMapping {
    private String sourceField;
    private String targetField;
    private String privacyAction = "none";
    private List<String> transformations = new ArrayList<>();

    public String getSourceField() { return sourceField; }
    public void setSourceField(String sourceField) { this.sourceField = sourceField; }
    public String getTargetField() { return targetField; }
    public void setTargetField(String targetField) { this.targetField = targetField; }
    public String getPrivacyAction() { return privacyAction; }
    public void setPrivacyAction(String privacyAction) { this.privacyAction = privacyAction; }
    public List<String> getTransformations() { return transformations; }
    public void setTransformations(List<String> transformations) {
        this.transformations = transformations == null ? new ArrayList<>() : new ArrayList<>(transformations);
    }
}
