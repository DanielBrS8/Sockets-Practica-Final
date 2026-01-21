package org.example.ServidorSocket;

public class Pregunta {
    private String texto;
    private String opcionA;
    private String opcionB;
    private String opcionC;
    private String opcionD;
    private char respuestaCorrecta;

    public Pregunta(String texto, String opcionA, String opcionB, String opcionC, String opcionD, char respuestaCorrecta) {
        this.texto = texto;
        this.opcionA = opcionA;
        this.opcionB = opcionB;
        this.opcionC = opcionC;
        this.opcionD = opcionD;
        this.respuestaCorrecta = respuestaCorrecta;
    }

    public String getTexto() {
        return texto;
    }

    public String getOpcionA() {
        return opcionA;
    }

    public String getOpcionB() {
        return opcionB;
    }

    public String getOpcionC() {
        return opcionC;
    }

    public String getOpcionD() {
        return opcionD;
    }

    public char getRespuestaCorrecta() {
        return respuestaCorrecta;
    }
}
