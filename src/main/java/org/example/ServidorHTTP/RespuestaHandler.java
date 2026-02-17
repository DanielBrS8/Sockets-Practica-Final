package org.example.ServidorHTTP;

public class RespuestaHandler {

    /**
     * Procesa una peticion POST /respuesta
     * @param body el cuerpo JSON de la peticion
     * @return String[2]: {codigoHTTP, jsonRespuesta}
     */
    public static String[] procesar(String body) {
        String id = extraerValor(body, "id");
        String resp = extraerValor(body, "respuesta");

        if (id == null || resp == null || resp.isEmpty()) {
            return new String[]{"400", "{\"ok\": false, \"error\": \"Faltan datos\"}"};
        }

        char respuesta = resp.toUpperCase().charAt(0);
        if (respuesta < 'A' || respuesta > 'D') {
            return new String[]{"400", "{\"ok\": false, \"error\": \"Respuesta invalida\"}"};
        }

        RespuestaDTO resultado = ServidorHTTP.procesarRespuesta(id, respuesta);

        if (resultado == null) {
            return new String[]{"404", "{\"ok\": false, \"error\": \"Jugador no encontrado\"}"};
        }

        return new String[]{"200", resultado.toJson()};
    }

    private static String extraerValor(String json, String clave) {
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
}
