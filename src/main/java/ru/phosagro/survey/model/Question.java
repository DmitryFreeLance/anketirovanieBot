package ru.phosagro.survey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Question {
    private String id;
    private QuestionType type;
    private String text;
    private List<Option> options;
    private Integer max; // for MULTI

    public String getId() { return id; }
    public QuestionType getType() { return type; }
    public String getText() { return text; }
    public List<Option> getOptions() { return options; }
    public Integer getMax() { return max == null ? 0 : max; }

    public void setId(String id) { this.id = id; }
    public void setType(QuestionType type) { this.type = type; }
    public void setText(String text) { this.text = text; }
    public void setOptions(List<Option> options) { this.options = options; }
    public void setMax(Integer max) { this.max = max; }
}