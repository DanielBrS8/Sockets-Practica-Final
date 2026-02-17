package org.example.ServidorHTTP;

public class RegistroHandler {

    /**
     * Procesa una peticion POST /registro
     * @param body el cuerpo JSON de la peticion
     * @return String[2]: {codigoHTTP, jsonRespuesta}
     */
    public static String[] procesar(String body) {
        String nombre = extraerValor(body, "nombre");

        if (nombre == null || nombre.isEmpty()) {
            nombre = "Jugador" + System.currentTimeMillis() % 1000;
        }

        String id = ServidorHTTP.registrarJugador(nombre);

        String json = String.format("{\"ok\": true, \"id\": \"%s\", \"nombre\": \"%s\"}", id, nombre);
        return new String[]{"200", json};
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
