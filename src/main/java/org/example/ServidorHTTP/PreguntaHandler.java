package org.example.ServidorHTTP;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class PreguntaHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            enviarRespuesta(exchange, 405, "{\"error\": \"Metodo no permitido\"}");
            return;
        }

        Pregunta p = ServidorHTTP.getPreguntaActual();

        if (p == null) {
            enviarRespuesta(exchange, 200, "{\"hay_pregunta\": false}");
            return;
        }

        String json = String.format(
            "{\"hay_pregunta\": true, \"numero\": %d, \"total\": %d, \"texto\": \"%s\", " +
            "\"opciones\": [\"%s\", \"%s\", \"%s\", \"%s\"]}",
            ServidorHTTP.getNumeroPregunta(),
            ServidorHTTP.getTotalPreguntas(),
            p.getTexto(),
            p.getOpcionA(), p.getOpcionB(), p.getOpcionC(), p.getOpcionD()
        );

        enviarRespuesta(exchange, 200, json);
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
