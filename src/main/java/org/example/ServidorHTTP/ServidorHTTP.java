package org.example.ServidorHTTP;

import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import com.opencsv.CSVReader;

import java.nio.charset.StandardCharsets;

public class ServidorHTTP {

    private static final int PUERTO = 7070;

    // Tu identidad TLS (tu keystore)
    private static final String KEYSTORE_PATH = "certs/server-keystore.p12"; // cargado desde classpath (resources)
    private static final char[] KEYSTORE_PASS = "changeit".toCharArray(); // <-- la tuya

    private static List<Pregunta> preguntas = new ArrayList<>();
    private static int preguntaActual = -1;
    private static long tiempoInicio = 0;

    private static Map<String, PrintWriter> clientesConectados = new ConcurrentHashMap<>();
    private static Map<String, String> jugadores = new ConcurrentHashMap<>();
    private static Map<String, Integer> puntuaciones = new ConcurrentHashMap<>();
    private static Map<String, RespuestaDTO> respuestas = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        inicializarPreguntas();

        SSLServerSocket serverSocket = crearSSLServerSocket(PUERTO);
        ExecutorService pool = Executors.newFixedThreadPool(20);

        System.out.println("=================================");
        System.out.println("  SERVIDOR TRIVIA (TLS) - Puerto " + PUERTO);
        System.out.println("=================================");
        System.out.println("Comandos: NEXT, RESET, RANKING");

        Thread hiloServidor = new Thread(() -> {
            while (true) {
                try {
                    SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                    pool.execute(() -> manejarConexion(clientSocket));
                } catch (IOException e) {
                    System.out.println("[ERROR] Aceptando conexion: " + e.getMessage());
                }
            }
        });
        hiloServidor.start();

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String cmd = scanner.nextLine().toUpperCase().trim();
            switch (cmd) {
                case "NEXT" -> siguientePregunta();
                case "RESET" -> {
                    preguntaActual = -1;
                    respuestas.clear();
                    broadcastHTTP("POST", "/api/kahoot-like/info",
                            "{\"message\":\"Juego reiniciado. Esperando nueva partida...\"}");
                    System.out.println("[*] Reiniciado");
                }
                case "RANKING" -> enviarRanking();
                default -> System.out.println("[!] Comando desconocido");
            }
        }
    }

    private static void manejarConexion(SSLSocket socket) {
        String jugadorId = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // 1) Leer join HTTP simulado
            String[] peticion = leerPeticionHTTP(in);
            if (peticion == null)
                return;

            String nickname = extraerValor(peticion[1], "nickname");
            if (nickname == null || nickname.isEmpty())
                nickname = "Jugador" + (System.currentTimeMillis() % 1000);

            // 2) Registrar jugador
            jugadorId = UUID.randomUUID().toString().substring(0, 8);
            jugadores.put(jugadorId, nickname);
            puntuaciones.put(jugadorId, 0);
            clientesConectados.put(jugadorId, out);

            System.out.println("[+] " + nickname + " conectado (ID: " + jugadorId + ")");

            // 3) Respuesta welcome (HTTP/1.1)
            enviarRespuestaHTTP(out, 200, "OK",
                    "{\"type\":\"welcome\",\"message\":\"Bienvenido al Trivia, " + nickname + "!\",\"players\":"
                            + clientesConectados.size() + "}");

            broadcastHTTP("POST", "/api/kahoot-like/info",
                    "{\"message\":\"" + nickname + " se ha unido! (" + clientesConectados.size() + " jugadores)\"}");

            // 4) Bucle: leer requests del cliente
            while (true) {
                String[] req = leerPeticionHTTP(in);
                if (req == null)
                    break;

                String requestLine = req[0];
                String body = req[1];

                if (requestLine.contains("/api/kahoot-like/quit")) {
                    enviarRespuestaHTTP(out, 200, "OK", "{\"type\":\"info\",\"message\":\"Hasta luego!\"}");
                    break;
                }

                if (requestLine.contains("/api/kahoot-like/answer")) {
                    String answer = extraerValor(body, "answer");
                    if (answer != null && answer.length() == 1) {
                        char resp = Character.toUpperCase(answer.charAt(0));
                        if (resp >= 'A' && resp <= 'D') {
                            procesarRespuestaJugador(jugadorId, resp, out);
                        } else {
                            enviarRespuestaHTTP(out, 400, "Bad Request",
                                    "{\"error\":\"Respuesta invalida. Usa A, B, C o D\"}");
                        }
                    } else {
                        enviarRespuestaHTTP(out, 400, "Bad Request",
                                "{\"error\":\"Respuesta invalida. Usa A, B, C o D\"}");
                    }
                }
            }

        } catch (IOException e) {
            // desconectado
        } finally {
            if (jugadorId != null) {
                String nombre = jugadores.get(jugadorId);
                clientesConectados.remove(jugadorId);
                System.out.println("[-] " + (nombre != null ? nombre : jugadorId) + " desconectado");
                broadcastHTTP("POST", "/api/kahoot-like/info",
                        "{\"message\":\"" + (nombre != null ? nombre : "Un jugador") + " se ha desconectado. ("
                                + clientesConectados.size() + " jugadores)\"}");
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ==========================
    // Lógica juego
    // ==========================
    private static void siguientePregunta() {
        preguntaActual++;
        respuestas.clear();
        tiempoInicio = System.currentTimeMillis();

        if (preguntaActual < preguntas.size()) {
            Pregunta p = preguntas.get(preguntaActual);
            System.out.println("[PREGUNTA " + (preguntaActual + 1) + "/" + preguntas.size() + "] " + p.getQuestion());

            String choicesJson = "[\"" + String.join("\",\"", p.getChoices()) + "\"]";
            String json = "{"
                    + "\"quizId\":\"" + (preguntaActual + 1) + "\","
                    + "\"question\":\"" + p.getQuestion() + "\","
                    + "\"choices\":" + choicesJson + ","
                    + "\"correctIndex\":" + p.getCorrectIndex() + ","
                    + "\"timeLimitSeconds\":" + p.getTimeLimitSeconds()
                    + "}";

            broadcastHTTP("POST", "/api/kahoot-like/questions", json);
        } else {
            broadcastHTTP("POST", "/api/kahoot-like/info",
                    "{\"message\":\"No hay mas preguntas! Juego terminado.\"}");
            enviarRanking();
            System.out.println("[!] No hay mas preguntas");
        }
    }

    private static synchronized void procesarRespuestaJugador(String id, char respuesta, PrintWriter out) {
        String nombre = jugadores.get(id);

        if (preguntaActual < 0 || preguntaActual >= preguntas.size()) {
            enviarRespuestaHTTP(out, 200, "OK",
                    "{\"type\":\"wait\",\"message\":\"No hay pregunta activa. Espera...\"}");
            return;
        }

        if (respuestas.containsKey(id)) {
            enviarRespuestaHTTP(out, 400, "Bad Request",
                    "{\"error\":\"Ya respondiste a esta pregunta\"}");
            return;
        }

        long tiempo = System.currentTimeMillis() - tiempoInicio;
        boolean correcta = (respuesta - 'A') == preguntas.get(preguntaActual).getCorrectIndex();

        respuestas.put(id, new RespuestaDTO(nombre, respuesta, tiempo, correcta, null));

        if (correcta) {
            int puntos = Math.max(100, 1000 - (int) (tiempo / 10));
            puntuaciones.merge(id, puntos, Integer::sum);
            enviarRespuestaHTTP(out, 200, "OK",
                    "{\"type\":\"result\",\"correct\":true,\"points\":" + puntos + ",\"timeMs\":" + tiempo + "}");
        } else {
            char correctaLetra = (char) ('A' + preguntas.get(preguntaActual).getCorrectIndex());
            enviarRespuestaHTTP(out, 200, "OK",
                    "{\"type\":\"result\",\"correct\":false,\"correctAnswer\":\"" + correctaLetra + "\",\"timeMs\":"
                            + tiempo + "}");
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
                if (pos > 1)
                    rankingsJson.append(",");
                rankingsJson.append("{\"position\":").append(pos)
                        .append(",\"name\":\"").append(nombre)
                        .append("\",\"points\":").append(entry.getValue())
                        .append("}");
                pos++;
            }
        }
        rankingsJson.append("]");

        broadcastHTTP("POST", "/api/kahoot-like/ranking",
                "{\"type\":\"ranking\",\"rankings\":" + rankingsJson + "}");
    }

    // ==========================
    // HTTP simulado (igual que tú)
    // ==========================
    private static void enviarPeticionHTTP(PrintWriter out, String method, String path, String jsonBody) {
        out.println(method + " " + path + " HTTP/1.1");
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
        out.flush();
    }

    private static void enviarRespuestaHTTP(PrintWriter out, int statusCode, String statusText, String jsonBody) {
        //preguntas, ranking, info
        out.println("HTTP/1.1 " + statusCode + " " + statusText);
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
        out.flush();
    }

    private static void broadcastHTTP(String method, String path, String jsonBody) {
        for (PrintWriter writer : clientesConectados.values()) {
            enviarPeticionHTTP(writer, method, path, jsonBody);
        }
    }

    private static String[] leerPeticionHTTP(BufferedReader in) throws IOException {
        //(join, answer, quit)
        String requestLine = in.readLine();
        if (requestLine == null)
            return null;

        String linea;
        while ((linea = in.readLine()) != null && !linea.isEmpty()) {
            // consumir headers
        }
        if (linea == null)
            return null;

        StringBuilder jsonBody = new StringBuilder();
        while ((linea = in.readLine()) != null) {
            jsonBody.append(linea);
            if (linea.trim().endsWith("}"))
                break;
        }

        return new String[] { requestLine, jsonBody.toString() };
    }

    // ==========================
    // TLS ServerSocket
    // ==========================
    private static SSLServerSocket crearSSLServerSocket(int puerto) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ServidorHTTP.class.getResourceAsStream("/" + KEYSTORE_PATH)) {
            if (is == null) throw new FileNotFoundException("No se encontró " + KEYSTORE_PATH + " en el classpath");
            ks.load(is, KEYSTORE_PASS);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket server = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(puerto);
        server.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });

    
        return server;
    }

    // ==========================
    // Datos
    // ==========================
    private static void inicializarPreguntas() {
        
        String ftpHost = "34.63.137.122"; 
        int ftpPort = 21;
        String ftpUser = "ftpquiz";
        String ftpPass = "ftpquiz";
        String remotePath = "/blooket/questions.csv";

        try {
            preguntas = cargarPreguntasDesdeFTP(ftpHost, ftpPort, ftpUser, ftpPass, remotePath);
            System.out.println("[*] Cargadas " + preguntas.size() + " preguntas desde FTP");
        } catch (Exception e) {
            System.out.println("[ERROR] No se pudo cargar desde FTP: " + e.getMessage());
            System.out.println("[*] Uso preguntas locales de fallback...");

            preguntas.clear();
            preguntas.add(new Pregunta("Cual es la capital de Francia?",
                    new String[] { "Londres", "Paris", "Madrid", "Berlin" }, 1, 20));
            preguntas.add(new Pregunta("Cuanto es 2+2?",
                    new String[] { "3", "5", "4", "22" }, 2, 20));
            preguntas.add(new Pregunta("Lenguaje de este proyecto?",
                    new String[] { "Python", "JavaScript", "C++", "Java" }, 3, 20));
        }
    }

    private static List<Pregunta> cargarPreguntasDesdeFTP(
            String host, int port, String user, String pass, String remotePath) throws Exception {

        FTPClient ftp = new FTPClient();
        List<Pregunta> out = new ArrayList<>();

        try {
            ftp.connect(host, port);
            if (!ftp.login(user, pass)) {
                throw new RuntimeException("Login FTP falló (usuario/pass incorrectos)");
            }

            // en cloud es lo recomendado
            ftp.enterLocalPassiveMode();

            // Para descargar ficheros mejor binario
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            // Abrimos stream del fichero remoto
            try (InputStream is = ftp.retrieveFileStream(remotePath)) {
                if (is == null) {
                    throw new IOException("No se pudo abrir " + remotePath + " en FTP (ruta no existe o permisos)");
                }

                try (CSVReader r = new CSVReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String[] row;
                    boolean first = true;

                    while ((row = r.readNext()) != null) {
                        if (first) {
                            first = false;
                            continue;
                        } // saltar cabecera

                        // Esperamos 7 columnas:
                        // Question Text,Answer 1,Answer 2,Answer 3,Answer 4,Time Limit (sec),Correct
                        // Answer(s)
                        if (row.length < 7)
                            continue;

                        String q = row[0];
                        String a1 = row[1];
                        String a2 = row[2];
                        String a3 = row[3];
                        String a4 = row[4];

                        int timeLimit = parseIntOrDefault(row[5], 20);

                        
                        int correctIndex = parseIntOrDefault(row[6], 1) - 1;
                        if (correctIndex < 0 || correctIndex > 3)
                            correctIndex = 0;

                        out.add(new Pregunta(q, new String[] { a1, a2, a3, a4 }, correctIndex, timeLimit));
                    }
                }
            }

            // Obligatorio tras retrieveFileStream sino se queda a medias
            if (!ftp.completePendingCommand()) {
                throw new IOException("FTP completePendingCommand() falló");
            }

            ftp.logout();
            return out;

        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int parseIntOrDefault(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String extraerValor(String json, String clave) {
        int idx = json.indexOf("\"" + clave + "\"");
        if (idx == -1)
            return null;
        idx = json.indexOf(":", idx);
        if (idx == -1)
            return null;
        idx = json.indexOf("\"", idx);
        if (idx == -1)
            return null;
        int fin = json.indexOf("\"", idx + 1);
        if (fin == -1)
            return null;
        return json.substring(idx + 1, fin);
    }
}
