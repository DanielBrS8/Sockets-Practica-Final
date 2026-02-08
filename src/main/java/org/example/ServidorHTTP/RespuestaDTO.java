package org.example.ServidorHTTP;

public class RespuestaDTO {
    private String nombre;
    private char respuesta;
    private long tiempoMs;
    private boolean correcta;
    private String error;

    public RespuestaDTO(String nombre, char respuesta, long tiempoMs, boolean correcta, String error) {
        this.nombre = nombre;
        this.respuesta = respuesta;
        this.tiempoMs = tiempoMs;
        this.correcta = correcta;
        this.error = error;
    }

    public String getNombre() {
        return nombre;
    }

    public char getRespuesta() {
        return respuesta;
    }

    public long getTiempoMs() {
        return tiempoMs;
    }

    public boolean isCorrecta() {
        return correcta;
    }

    public String getError() {
        return error;
    }

    public String toJson() {
        if (error != null) {
            return String.format("{\"ok\": false, \"error\": \"%s\"}", error);
        }
        return String.format(
            "{\"ok\": true, \"tiempo\": %d, \"correcta\": %b}",
            tiempoMs, correcta
        );
    }
}
