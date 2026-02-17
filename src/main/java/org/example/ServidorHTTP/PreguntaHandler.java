package org.example.ServidorHTTP;

public class PreguntaHandler {

    /**
     * Procesa una peticion GET /pregunta
     * @return String[2]: {codigoHTTP, jsonRespuesta}
     */
    public static String[] procesar() {
        Pregunta p = ServidorHTTP.getPreguntaActual();

        if (p == null) {
            return new String[]{"200", "{\"hay_pregunta\": false}"};
        }

        String json = String.format(
            "{\"hay_pregunta\": true, \"numero\": %d, \"total\": %d, \"texto\": \"%s\", " +
            "\"opciones\": [\"%s\", \"%s\", \"%s\", \"%s\"]}",
            ServidorHTTP.getNumeroPregunta(),
            ServidorHTTP.getTotalPreguntas(),
            p.getTexto(),
            p.getOpcionA(), p.getOpcionB(), p.getOpcionC(), p.getOpcionD()
        );

        return new String[]{"200", json};
    }
}
