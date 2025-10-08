package ru.phosagro.survey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Survey {
    private String title;
    private String welcome;
    private String startButton;
    private List<Question> questions;
    private String finish;

    public String getTitle() { return title; }
    public String getWelcome() { return welcome; }
    public String getStartButton() { return startButton; }
    public List<Question> getQuestions() { return questions; }
    public String getFinish() { return finish; }

    public void setTitle(String title) { this.title = title; }
    public void setWelcome(String welcome) { this.welcome = welcome; }
    public void setStartButton(String startButton) { this.startButton = startButton; }
    public void setQuestions(List<Question> questions) { this.questions = questions; }
    public void setFinish(String finish) { this.finish = finish; }
}