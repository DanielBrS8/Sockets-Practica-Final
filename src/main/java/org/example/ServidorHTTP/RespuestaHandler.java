package org.example.ServidorHTTP;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class RespuestaHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            enviarRespuesta(exchange, 405, "{\"error\": \"Metodo no permitido\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String id = extraerValor(body, "id");
        String resp = extraerValor(body, "respuesta");

        if (id == null || resp == null || resp.isEmpty()) {
            enviarRespuesta(exchange, 400, "{\"ok\": false, \"error\": \"Faltan datos\"}");
            return;
        }

        char respuesta = resp.toUpperCase().charAt(0);
        if (respuesta < 'A' || respuesta > 'D') {
            enviarRespuesta(exchange, 400, "{\"ok\": false, \"error\": \"Respuesta invalida\"}");
            return;
        }

        RespuestaDTO resultado = ServidorHTTP.procesarRespuesta(id, respuesta);

        if (resultado == null) {
            enviarRespuesta(exchange, 404, "{\"ok\": false, \"error\": \"Jugador no encontrado\"}");
            return;
        }

        enviarRespuesta(exchange, 200, resultado.toJson());
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
