package org.example.ServidorSocket;

// Clase para almacenar respuestas de clientes
class RespuestaCliente {
    private String nombre;
    private char respuesta;
    private long tiempoMs;
    private boolean correcta;

    public RespuestaCliente(String nombre, char respuesta, long tiempoMs, boolean correcta) {
        this.nombre = nombre;
        this.respuesta = respuesta;
        this.tiempoMs = tiempoMs;
        this.correcta = correcta;
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
}
