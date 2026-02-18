package org.example.ServidorHTTP;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServidorHTTP {

    private static final int PUERTO = 8080;

    // Preguntas del juego
    private static List<Pregunta> preguntas = new ArrayList<>();
    private static int preguntaActual = -1;
    private static long tiempoInicio = 0;

    // Clientes conectados: id -> PrintWriter
    private static Map<String, PrintWriter> clientesConectados = new ConcurrentHashMap<>();
    // Nombres: id -> nombre
    private static Map<String, String> jugadores = new ConcurrentHashMap<>();
    // Puntuaciones: id -> puntos
    private static Map<String, Integer> puntuaciones = new ConcurrentHashMap<>();
    // Respuestas de la pregunta actual: id -> RespuestaDTO
    private static Map<String, RespuestaDTO> respuestas = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        inicializarPreguntas();

        ServerSocket serverSocket = new ServerSocket(PUERTO);
        ExecutorService pool = Executors.newFixedThreadPool(10);

        System.out.println("=================================");
        System.out.println("  SERVIDOR TRIVIA - Puerto " + PUERTO);
        System.out.println("=================================");
        System.out.println("Comandos: NEXT, RESET, RANKING");

        // Hilo para aceptar conexiones
        Thread hiloServidor = new Thread(() -> {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    pool.execute(() -> manejarConexion(clientSocket));
                } catch (IOException e) {
                    System.out.println("[ERROR] Aceptando conexion: " + e.getMessage());
                }
            }
        });
        hiloServidor.setDaemon(true);
        hiloServidor.start();

        // Comandos de consola en hilo principal
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String cmd = scanner.nextLine().toUpperCase().trim();
            switch (cmd) {
                case "NEXT":
                    siguientePregunta();
                    break;
                case "RESET":
                    preguntaActual = -1;
                    respuestas.clear();
                    broadcastHTTP("POST", "/api/kahoot-like/info",
                        "{\"message\": \"Juego reiniciado. Esperando nueva partida...\"}");
                    System.out.println("[*] Reiniciado");
                    break;
                case "RANKING":
                    enviarRanking();
                    break;
            }
        }
    }

    private static void manejarConexion(Socket socket) {
        String jugadorId = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            // 1. Leer peticion HTTP de join del cliente
            String[] peticion = leerPeticionHTTP(in);
            if (peticion == null) return;

            // 2. Extraer nickname del JSON del body
            String nickname = extraerValor(peticion[1], "nickname");
            if (nickname == null || nickname.isEmpty()) {
                nickname = "Jugador" + System.currentTimeMillis() % 1000;
            }

            // 3. Registrar jugador
            jugadorId = UUID.randomUUID().toString().substring(0, 8);
            jugadores.put(jugadorId, nickname);
            puntuaciones.put(jugadorId, 0);
            clientesConectados.put(jugadorId, out);

            System.out.println("[+] " + nickname + " conectado (ID: " + jugadorId + ")");

            // 4. Enviar respuesta HTTP de bienvenida
            enviarRespuestaHTTP(out, 200, "OK",
                "{\"type\": \"welcome\", \"message\": \"Bienvenido al Trivia, " + nickname + "!\", " +
                "\"players\": " + clientesConectados.size() + "}");

            // Notificar a todos
            broadcastHTTP("POST", "/api/kahoot-like/info",
                "{\"message\": \"" + nickname + " se ha unido! (" + clientesConectados.size() + " jugadores)\"}");

            // 5. Bucle: leer peticiones HTTP del cliente (respuestas a preguntas)
            while (true) {
                String[] req = leerPeticionHTTP(in);
                if (req == null) break; // desconectado

                String requestLine = req[0];
                String body = req[1];

                if (requestLine.contains("/api/kahoot-like/quit")) {
                    enviarRespuestaHTTP(out, 200, "OK",
                        "{\"type\": \"info\", \"message\": \"Hasta luego!\"}");
                    break;
                }

                if (requestLine.contains("/api/kahoot-like/answer")) {
                    String answer = extraerValor(body, "answer");
                    if (answer != null && answer.length() == 1) {
                        char resp = answer.toUpperCase().charAt(0);
                        if (resp >= 'A' && resp <= 'D') {
                            procesarRespuestaJugador(jugadorId, resp, out);
                        } else {
                            enviarRespuestaHTTP(out, 400, "Bad Request",
                                "{\"error\": \"Respuesta invalida. Usa A, B, C o D\"}");
                        }
                    } else {
                        enviarRespuestaHTTP(out, 400, "Bad Request",
                            "{\"error\": \"Respuesta invalida. Usa A, B, C o D\"}");
                    }
                }
            }

        } catch (IOException e) {
            // Cliente desconectado
        } finally {
            if (jugadorId != null) {
                String nombre = jugadores.get(jugadorId);
                clientesConectados.remove(jugadorId);
                System.out.println("[-] " + (nombre != null ? nombre : jugadorId) + " desconectado");
                broadcastHTTP("POST", "/api/kahoot-like/info",
                    "{\"message\": \"" + (nombre != null ? nombre : "Un jugador") +
                    " se ha desconectado. (" + clientesConectados.size() + " jugadores)\"}");
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // === Logica del juego ===

    private static void siguientePregunta() {
        preguntaActual++;
        respuestas.clear();
        tiempoInicio = System.currentTimeMillis();

        if (preguntaActual < preguntas.size()) {
            Pregunta p = preguntas.get(preguntaActual);
            System.out.println("[PREGUNTA " + (preguntaActual + 1) + "/" + preguntas.size() + "] " + p.getQuestion());

            // Enviar pregunta en formato HTTP del profesor
            String choicesJson = "[\"" + String.join("\", \"", p.getChoices()) + "\"]";
            String json = "{" +
                "\"quizId\": \"" + (preguntaActual + 1) + "\", " +
                "\"question\": \"" + p.getQuestion() + "\", " +
                "\"choices\": " + choicesJson + ", " +
                "\"correctIndex\": " + p.getCorrectIndex() + ", " +
                "\"timeLimitSeconds\": " + p.getTimeLimitSeconds() +
                "}";
            broadcastHTTP("POST", "/api/kahoot-like/questions", json);
        } else {
            broadcastHTTP("POST", "/api/kahoot-like/info",
                "{\"message\": \"No hay mas preguntas! Juego terminado.\"}");
            enviarRanking();
            System.out.println("[!] No hay mas preguntas");
        }
    }

    private static synchronized void procesarRespuestaJugador(String id, char respuesta, PrintWriter out) {
        String nombre = jugadores.get(id);

        if (preguntaActual < 0 || preguntaActual >= preguntas.size()) {
            enviarRespuestaHTTP(out, 200, "OK",
                "{\"type\": \"wait\", \"message\": \"No hay pregunta activa. Espera...\"}");
            return;
        }

        if (respuestas.containsKey(id)) {
            enviarRespuestaHTTP(out, 400, "Bad Request",
                "{\"error\": \"Ya respondiste a esta pregunta\"}");
            return;
        }

        long tiempo = System.currentTimeMillis() - tiempoInicio;
        // correctIndex es 0-3, respuesta A-D => A=0, B=1, C=2, D=3
        boolean correcta = (respuesta - 'A') == preguntas.get(preguntaActual).getCorrectIndex();

        RespuestaDTO dto = new RespuestaDTO(nombre, respuesta, tiempo, correcta, null);
        respuestas.put(id, dto);

        if (correcta) {
            int puntos = Math.max(100, 1000 - (int)(tiempo / 10));
            puntuaciones.merge(id, puntos, Integer::sum);
            enviarRespuestaHTTP(out, 200, "OK",
                "{\"type\": \"result\", \"correct\": true, \"points\": " + puntos + ", \"timeMs\": " + tiempo + "}");
        } else {
            char correctaLetra = (char) ('A' + preguntas.get(preguntaActual).getCorrectIndex());
            enviarRespuestaHTTP(out, 200, "OK",
                "{\"type\": \"result\", \"correct\": false, \"correctAnswer\": \"" + correctaLetra + "\", \"timeMs\": " + tiempo + "}");
        }

        System.out.println("[R] " + nombre + " -> " + respuesta + " (" + tiempo + "ms) " + (correcta ? "OK" : "X"));

        if (respuestas.size() == clientesConectados.size()) {
            System.out.println("[*] Todos respondieron");
            enviarRanking();
        }
    }

    private static void enviarRanking() {
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuaciones.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        StringBuilder rankingsJson = new StringBuilder("[");
        int pos = 1;
        for (Map.Entry<String, Integer> entry : ranking) {
            String nombre = jugadores.get(entry.getKey());
            if (nombre != null) {
                if (pos > 1) rankingsJson.append(", ");
                rankingsJson.append("{\"position\": ").append(pos)
                    .append(", \"name\": \"").append(nombre)
                    .append("\", \"points\": ").append(entry.getValue()).append("}");
                pos++;
            }
        }
        rankingsJson.append("]");

        broadcastHTTP("POST", "/api/kahoot-like/ranking",
            "{\"type\": \"ranking\", \"rankings\": " + rankingsJson + "}");
    }

    // === Envio/Lectura HTTP ===

    // Enviar peticion HTTP (servidor -> cliente, para preguntas/info/ranking)
    private static void enviarPeticionHTTP(PrintWriter out, String method, String path, String jsonBody) {
        out.println(method + " " + path + " HTTP/1.1");
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
    }

    // Enviar respuesta HTTP (servidor -> cliente, para resultados)
    private static void enviarRespuestaHTTP(PrintWriter out, int statusCode, String statusText, String jsonBody) {
        out.println("HTTP/1.1 " + statusCode + " " + statusText);
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
    }

    // Broadcast HTTP POST a todos los clientes
    private static void broadcastHTTP(String method, String path, String jsonBody) {
        for (PrintWriter writer : clientesConectados.values()) {
            enviarPeticionHTTP(writer, method, path, jsonBody);
        }
    }

    // Leer una peticion HTTP completa (request line + headers + body JSON)
    private static String[] leerPeticionHTTP(BufferedReader in) throws IOException {
        // 1. Leer request line (ej: POST /api/kahoot-like/answer HTTP/1.1)
        String requestLine = in.readLine();
        if (requestLine == null) return null;

        // 2. Leer cabeceras hasta linea vacia
        String linea;
        while ((linea = in.readLine()) != null && !linea.isEmpty()) {
            // consumir cabeceras
        }
        if (linea == null) return null;

        // 3. Leer body JSON (lineas hasta cerrar llave)
        StringBuilder jsonBody = new StringBuilder();
        while ((linea = in.readLine()) != null) {
            jsonBody.append(linea);
            if (linea.trim().endsWith("}")) break;
        }

        return new String[]{requestLine, jsonBody.toString()};
    }

    // === Utilidades ===

    private static void inicializarPreguntas() {
        preguntas.add(new Pregunta(
            "Cual es la capital de Francia?",
            new String[]{"Londres", "Paris", "Madrid", "Berlin"}, 1, 20));
        preguntas.add(new Pregunta(
            "Cuanto es 2+2?",
            new String[]{"3", "5", "4", "22"}, 2, 20));
        preguntas.add(new Pregunta(
            "Lenguaje de este proyecto?",
            new String[]{"Python", "JavaScript", "C++", "Java"}, 3, 20));
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
