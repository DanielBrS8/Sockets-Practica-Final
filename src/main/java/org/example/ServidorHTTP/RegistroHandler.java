package org.example.ServidorHTTP;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class RegistroHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            enviarRespuesta(exchange, 405, "{\"error\": \"Metodo no permitido\"}");
            return;
        }

        // Leer body
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String nombre = extraerValor(body, "nombre");

        if (nombre == null || nombre.isEmpty()) {
            nombre = "Jugador" + System.currentTimeMillis() % 1000;
        }

        String id = ServidorHTTP.registrarJugador(nombre);

        String json = String.format("{\"ok\": true, \"id\": \"%s\", \"nombre\": \"%s\"}", id, nombre);
        enviarRespuesta(exchange, 200, json);
    }

    private String extraerValor(String json, String clave) {
        int idx = json.indexOf("\"" + clave + "\"");
        if (idx == -1) return null;
        idx = json.indexOf(":", idx);
        if (idx == -1) return null;
        idx = json.indexOf("\"", idx);
        if (idx == -1) return null;
        int fin = json.indexOf("\"", idx + 1);
        if (fin == -1) return null;
        return json.substring(idx + 1, fin);
    }

    private void enviarRespuesta(HttpExchange exchange, int codigo, String respuesta) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
