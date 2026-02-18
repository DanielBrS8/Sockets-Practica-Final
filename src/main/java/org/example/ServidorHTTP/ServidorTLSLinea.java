package org.example.ServidorHTTP;

import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServidorTLSLinea {

    private static final int PUERTO = 7070;

    // Tu identidad como servidor (tu .p12)
    private static final String KEYSTORE_PATH = "certs/server-keystore.p12";
    private static final char[] KEYSTORE_PASS = "changeit".toCharArray(); // <-- pon la tuya

    // Juego
    private static final List<Pregunta> preguntas = new ArrayList<>();
    private static int preguntaActual = -1;
    private static long tiempoInicio = 0;

    // Clientes
    private static final Map<String, PrintWriter> clientesConectados = new ConcurrentHashMap<>();
    private static final Map<String, String> jugadores = new ConcurrentHashMap<>();
    private static final Map<String, Integer> puntuaciones = new ConcurrentHashMap<>();
    private static final Map<String, RespuestaDTO> respuestas = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        inicializarPreguntas();

        SSLServerSocket serverSocket = crearSSLServerSocket(PUERTO);
        ExecutorService pool = Executors.newFixedThreadPool(20);

        System.out.println("=================================");
        System.out.println("  SERVIDOR TRIVIA - Puerto " + PUERTO);
        System.out.println("  (TLS + Protocolo por lineas)");
        System.out.println("=================================");
        System.out.println("Comandos: NEXT, RESET, RANKING");

        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    SSLSocket client = (SSLSocket) serverSocket.accept();
                    pool.execute(() -> manejarConexion(client));
                } catch (IOException e) {
                    System.out.println("[ERROR] Aceptando conexion: " + e.getMessage());
                }
            }
        });
        acceptor.start();

        // Consola del servidor
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String cmd = scanner.nextLine().trim().toUpperCase();
            switch (cmd) {
                case "NEXT" -> siguientePregunta();
                case "RESET" -> {
                    preguntaActual = -1;
                    respuestas.clear();
                    broadcast("INFO:Juego reiniciado. Esperando nueva partida...");
                    System.out.println("[*] Reiniciado");
                }
                case "RANKING" -> enviarRanking();
                default -> System.out.println("[!] Comando desconocido (NEXT/RESET/RANKING)");
            }
        }
    }

    private static void manejarConexion(SSLSocket socket) {
        String jugadorId = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // 1) Leer handshake HTTP (request line + headers hasta linea vacia)
            String requestLine = in.readLine();
            if (requestLine == null)
                return;

            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // consumir headers
            }
            if (line == null)
                return;

            // 2) Leer body JSON multilinea hasta una linea que sea "}"
            StringBuilder jsonBody = new StringBuilder();
            while ((line = in.readLine()) != null) {
                jsonBody.append(line);
                if (line.trim().equals("}"))
                    break;
            }

            // 3) Extraer nickname
            String nickname = extraerValor(jsonBody.toString(), "nickname");

            // 4) Protocolo de tu amigo: pedir nombre
            out.println("NOMBRE:Introduce tu nombre");
            String nombreRecibido = in.readLine();
            if (nombreRecibido != null && !nombreRecibido.trim().isEmpty()) {
                nickname = nombreRecibido.trim();
            }
            if (nickname == null || nickname.isEmpty()) {
                nickname = "Jugador" + (System.currentTimeMillis() % 1000);
            }

            // 5) Registrar jugador
            jugadorId = UUID.randomUUID().toString().substring(0, 8);
            jugadores.put(jugadorId, nickname);
            puntuaciones.put(jugadorId, 0);
            clientesConectados.put(jugadorId, out);

            System.out.println("[+] " + nickname + " conectado (ID: " + jugadorId + ")");

            // 6) Bienvenida e info
            out.println("BIENVENIDO:Bienvenido al Trivia, " + nickname + "!");
            out.println("INFO:Jugadores conectados: " + clientesConectados.size());
            out.println("INFO:Escribe A/B/C/D para responder, o /salir para salir.");

            broadcast("INFO:" + nickname + " se ha unido! (" + clientesConectados.size() + " jugadores)");

            // 7) Bucle lectura: A/B/C/D o /salir
            String msg;
            while ((msg = in.readLine()) != null) {
                msg = msg.trim();

                if (msg.equalsIgnoreCase("/salir")) {
                    out.println("INFO:Hasta luego!");
                    break;
                }

                if (msg.length() == 1) {
                    char resp = Character.toUpperCase(msg.charAt(0));
                    if (resp >= 'A' && resp <= 'D') {
                        procesarRespuestaJugador(jugadorId, resp, out);
                    } else {
                        out.println("ERROR:Respuesta invalida. Usa A, B, C o D");
                    }
                } else {
                    out.println("ERROR:Comando no reconocido. Usa A/B/C/D o /salir");
                }
            }

        } catch (IOException e) {
            // desconexión
        } finally {
            if (jugadorId != null) {
                String nombre = jugadores.get(jugadorId);
                clientesConectados.remove(jugadorId);
                System.out.println("[-] " + (nombre != null ? nombre : jugadorId) + " desconectado");
                broadcast("INFO:" + (nombre != null ? nombre : "Un jugador") +
                        " se ha desconectado. (" + clientesConectados.size() + " jugadores)");
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ===== Lógica del juego =====

    private static void siguientePregunta() {
        preguntaActual++;
        respuestas.clear();
        tiempoInicio = System.currentTimeMillis();

        if (preguntaActual < preguntas.size()) {
            Pregunta p = preguntas.get(preguntaActual);

            System.out.println("[PREGUNTA " + (preguntaActual + 1) + "/" + preguntas.size() + "] " + p.texto);

            broadcast("PREGUNTA:" + p.texto);
            broadcast("OPCION_A:" + p.opciones[0]);
            broadcast("OPCION_B:" + p.opciones[1]);
            broadcast("OPCION_C:" + p.opciones[2]);
            broadcast("OPCION_D:" + p.opciones[3]);
            broadcast("INFO:Responde con A, B, C o D");

        } else {
            broadcast("INFO:No hay mas preguntas! Juego terminado.");
            enviarRanking();
            System.out.println("[!] No hay mas preguntas");
        }
    }

    private static synchronized void procesarRespuestaJugador(String id, char respuesta, PrintWriter out) {
        String nombre = jugadores.get(id);

        if (preguntaActual < 0 || preguntaActual >= preguntas.size()) {
            out.println("ESPERA:No hay pregunta activa. Espera...");
            return;
        }

        if (respuestas.containsKey(id)) {
            out.println("ERROR:Ya respondiste a esta pregunta");
            return;
        }

        long tiempo = System.currentTimeMillis() - tiempoInicio;
        boolean correcta = (respuesta - 'A') == preguntas.get(preguntaActual).correctIndex;

        respuestas.put(id, new RespuestaDTO(nombre, respuesta, tiempo, correcta));

        if (correcta) {
            int puntos = Math.max(100, 1000 - (int) (tiempo / 10));
            puntuaciones.merge(id, puntos, Integer::sum);
            out.println("RECIBIDO:Correcto! +" + puntos + " puntos (" + tiempo + "ms)");
        } else {
            char correctaLetra = (char) ('A' + preguntas.get(preguntaActual).correctIndex);
            out.println("RECIBIDO:Incorrecto. La respuesta era " + correctaLetra + " (" + tiempo + "ms)");
        }

        System.out.println("[R] " + nombre + " -> " + respuesta + " (" + tiempo + "ms) " + (correcta ? "OK" : "X"));

        if (respuestas.size() == clientesConectados.size()) {
            System.out.println("[*] Todos respondieron");
            enviarRanking();
        }
    }

    private static void enviarRanking() {
        broadcast("RANKING:--- RANKING ---");

        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuaciones.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        int pos = 1;
        for (Map.Entry<String, Integer> e : ranking) {
            String nombre = jugadores.get(e.getKey());
            if (nombre != null) {
                broadcast("RANKING:" + pos + ". " + nombre + " - " + e.getValue() + " pts");
                pos++;
            }
        }
        broadcast("RANKING:-----------------");
    }

    private static void broadcast(String mensaje) {
        for (PrintWriter w : clientesConectados.values()) {
            w.println(mensaje);
        }
    }

    // ===== TLS =====

    private static SSLServerSocket crearSSLServerSocket(int puerto) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Paths.get(KEYSTORE_PATH))) {
            ks.load(is, KEYSTORE_PASS);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket server = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(puerto);
        server.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });

        // OJO: No activamos mTLS aquí porque el cliente de tu amigo NO envía
        // certificado.
        // Si algún día quieres mTLS:
        // server.setNeedClientAuth(true); (y entonces el cliente deberá usar
        // KeyManagerFactory)
        return server;
    }

    // ===== Datos / utilidades =====

    private static void inicializarPreguntas() {
        preguntas.add(new Pregunta(
                "Cual es la capital de Francia?",
                new String[] { "Londres", "Paris", "Madrid", "Berlin" },
                1));
        preguntas.add(new Pregunta(
                "Cuanto es 2+2?",
                new String[] { "3", "5", "4", "22" },
                2));
        preguntas.add(new Pregunta(
                "Lenguaje de este proyecto?",
                new String[] { "Python", "JavaScript", "C++", "Java" },
                3));
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

    static class Pregunta {
        final String texto;
        final String[] opciones; // 4
        final int correctIndex; // 0..3

        Pregunta(String texto, String[] opciones, int correctIndex) {
            this.texto = texto;
            this.opciones = opciones;
            this.correctIndex = correctIndex;
        }
    }

    static class RespuestaDTO {
        final String nombre;
        final char respuesta;
        final long tiempoMs;
        final boolean correcta;

        RespuestaDTO(String nombre, char respuesta, long tiempoMs, boolean correcta) {
            this.nombre = nombre;
            this.respuesta = respuesta;
            this.tiempoMs = tiempoMs;
            this.correcta = correcta;
        }
    }
}
