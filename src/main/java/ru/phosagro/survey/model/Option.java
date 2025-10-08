package ru.phosagro.survey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Option {
    private String id;
    private String text;
    private boolean other;

    public String getId() { return id; }
    public String getText() { return text; }
    public boolean isOther() { return other; }

    public void setId(String id) { this.id = id; }
    public void setText(String text) { this.text = text; }
    public void setOther(boolean other) { this.other = other; }
}