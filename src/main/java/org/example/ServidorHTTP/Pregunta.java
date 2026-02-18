package org.example.ServidorHTTP;

public class Pregunta {
    private String question;
    private String[] choices;
    private int correctIndex;
    private int timeLimitSeconds;

    public Pregunta(String question, String[] choices, int correctIndex, int timeLimitSeconds) {
        this.question = question;
        this.choices = choices;
        this.correctIndex = correctIndex;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public String getQuestion() { return question; }
    public String[] getChoices() { return choices; }
    public int getCorrectIndex() { return correctIndex; }
    public int getTimeLimitSeconds() { return timeLimitSeconds; }
}
