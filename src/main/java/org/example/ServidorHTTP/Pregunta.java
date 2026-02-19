package org.example.ServidorHTTP;

public class Pregunta {
    private final String question;
    private final String[] choices;
    private final int correctIndex;
    private final int timeLimitSeconds;

    // Constructor completo (el que tu servidor está usando)
    public Pregunta(String question, String[] choices, int correctIndex, int timeLimitSeconds) {
        this.question = question;
        this.choices = choices;
        this.correctIndex = correctIndex;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    // Si quieres mantener compatibilidad con código antiguo:
    public Pregunta(String question, String[] choices, int correctIndex) {
        this(question, choices, correctIndex, 20);
    }

    public String getQuestion() { return question; }
    public String[] getChoices() { return choices; }
    public int getCorrectIndex() { return correctIndex; }
    public int getTimeLimitSeconds() { return timeLimitSeconds; }
}
