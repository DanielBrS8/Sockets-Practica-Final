package org.example.Cliente;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Scanner;

public class ClienteHTTP {

    private static final String DEFAULT_IP = "localhost";
    private static final int DEFAULT_PORT = 7070;

    // Defaults (cámbialos si quieres)
    private static final String TRUSTSTORE_MIO = "certs/client-truststore.p12";
    private static final String TRUSTSTORE_AMIGO = "cert-raidel/server-truststore.p12";
    private static final String DEFAULT_PASS = "changeit";

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        System.out.println("--- TRIVIA CLIENTE (TLS + HTTP simulado) ---");

        System.out.print("IP del servidor (enter = localhost): ");
        String ip = scanner.nextLine().trim();
        if (ip.isEmpty()) ip = DEFAULT_IP;

        System.out.print("Puerto (enter = 7070): ");
        String p = scanner.nextLine().trim();
        int port = p.isEmpty() ? DEFAULT_PORT : Integer.parseInt(p);

        System.out.print("Introduce tu nombre de jugador: ");
        String miNombre = scanner.nextLine().trim();
        if (miNombre.isEmpty()) miNombre = "Jugador";

        // Elegir truststore (para poder conectarte a tu server o al de tu amigo)
        String truststorePath = (ip.equals("localhost") || ip.equals("127.0.0.1")) ? TRUSTSTORE_MIO : TRUSTSTORE_AMIGO;

        System.out.print("Truststore (enter = " + truststorePath + "): ");
        String tsIn = scanner.nextLine().trim();
        if (!tsIn.isEmpty()) truststorePath = tsIn;

        System.out.print("Password truststore (enter = " + DEFAULT_PASS + "): ");
        String passIn = scanner.nextLine().trim();
        char[] trustPass = (passIn.isEmpty() ? DEFAULT_PASS : passIn).toCharArray();

        try (SSLSocket socket = crearSSLSocket(ip, port, truststorePath, trustPass);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            // 1) JOIN: TU protocolo bueno (HTTP simulado + JSON en 1 linea)
            enviarPeticionHTTP(out, "POST", "/api/kahoot-like/join",
                    "{\"action\":\"join_game\",\"playerId\":\"" + System.currentTimeMillis() + "\",\"nickname\":\"" + miNombre + "\",\"client\":\"JavaConsole\"}");

            // 2) Listener: lee mensajes HTTP simulados del servidor
            new Thread(() -> {
                try {
                    while (true) {
                        String[] mensaje = leerMensajeHTTP(in);
                        if (mensaje == null) break;

                        String firstLine = mensaje[0];
                        String body = mensaje[1];

                        // Peticiones del servidor (POST ...): pregunta/info/ranking
                        if (firstLine.startsWith("POST")) {
                            if (firstLine.contains("/api/kahoot-like/questions")) {
                                mostrarPregunta(body);
                            } else if (firstLine.contains("/api/kahoot-like/ranking")) {
                                mostrarRanking(body);
                            } else if (firstLine.contains("/api/kahoot-like/info")) {
                                String msg = extraerValor(body, "message");
                                System.out.println("[INFO] " + msg);
                            }
                        }
                        // Respuestas del servidor (HTTP/1.1 ...): welcome/result/wait/error
                        else if (firstLine.startsWith("HTTP/1.1")) {
                            String type = extraerValor(body, "type");
                            if (type != null) {
                                switch (type) {
                                    case "welcome" -> {
                                        System.out.println(">>> " + extraerValor(body, "message"));
                                        System.out.println("[INFO] Esperando a que el host lance una pregunta...");
                                    }
                                    case "result" -> mostrarResultado(body);
                                    case "wait" -> System.out.println("[ESPERA] " + extraerValor(body, "message"));
                                }
                            }
                            String error = extraerValor(body, "error");
                            if (error != null) System.out.println("[ERROR] " + error);
                        }
                    }
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                } catch (IOException e) {
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                }
            }).start();

            // 3) Enviar respuestas: TU protocolo bueno (POST /answer con JSON)
            while (scanner.hasNextLine()) {
                String userInput = scanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("/salir")) {
                    enviarPeticionHTTP(out, "POST", "/api/kahoot-like/quit", "{\"action\":\"quit\"}");
                    break;
                }

                if (userInput.length() == 1) {
                    char c = Character.toUpperCase(userInput.charAt(0));
                    if (c >= 'A' && c <= 'D') {
                        enviarPeticionHTTP(out, "POST", "/api/kahoot-like/answer", "{\"answer\":\"" + c + "\"}");
                    } else {
                        System.out.println("[!] Solo puedes responder A, B, C o D");
                    }
                } else {
                    System.out.println("[!] Solo puedes responder A, B, C o D (o /salir)");
                }
            }

        } catch (Exception e) {
            System.err.println("No se pudo conectar al servidor.");
            e.printStackTrace();
        }
    }

    // =========================
    // TLS
    // =========================
    private static SSLSocket crearSSLSocket(String host, int port, String truststorePath, char[] trustPass) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Paths.get(truststorePath))) {
            ts.load(is, trustPass);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        SSLSocketFactory factory = ctx.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();

        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(15000);

        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        socket.startHandshake();

        System.out.println("TLS OK -> " + socket.getSession().getProtocol());
        return socket;
    }

    // =========================
    // HTTP simulado
    // =========================
    private static void enviarPeticionHTTP(PrintWriter out, String method, String path, String jsonBody) {
        out.println(method + " " + path + " HTTP/1.1");
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
        out.flush();
    }

    private static String[] leerMensajeHTTP(BufferedReader in) throws IOException {
        String firstLine = in.readLine();
        if (firstLine == null) return null;

        String linea;
        while ((linea = in.readLine()) != null && !linea.isEmpty()) {
            // consumir headers
        }
        if (linea == null) return null;

        StringBuilder jsonBody = new StringBuilder();
        while ((linea = in.readLine()) != null) {
            jsonBody.append(linea);
            if (linea.trim().endsWith("}")) break;
        }

        return new String[]{firstLine, jsonBody.toString()};
    }

    // =========================
    // Mostrar
    // =========================
    private static void mostrarPregunta(String json) {
        String question = extraerValor(json, "question");
        System.out.println("\n=== PREGUNTA: " + question + " ===");

        int start = json.indexOf("[");
        int end = json.indexOf("]");
        if (start != -1 && end != -1) {
            String choicesStr = json.substring(start + 1, end);
            String[] choices = choicesStr.replace("\"", "").split(",");
            char letra = 'A';
            for (String choice : choices) {
                System.out.println("   " + letra + ") " + choice.trim());
                letra++;
            }
        }
        System.out.println("Responde con A, B, C o D:");
    }

    private static void mostrarResultado(String body) {
        boolean correct = body.contains("\"correct\": true") || body.contains("\"correct\":true");
        if (correct) {
            String points = extraerValorNumerico(body, "points");
            String time = extraerValorNumerico(body, "timeMs");
            System.out.println("[OK] Correcto! +" + points + " puntos (" + time + "ms)");
        } else {
            String correctAnswer = extraerValor(body, "correctAnswer");
            String time = extraerValorNumerico(body, "timeMs");
            System.out.println("[OK] Incorrecto. La respuesta era " + correctAnswer + " (" + time + "ms)");
        }
    }

    private static void mostrarRanking(String body) {
        System.out.println("\n--- RANKING ---");
        int start = body.indexOf("[");
        int end = body.lastIndexOf("]");
        if (start != -1 && end != -1) {
            String rankingsStr = body.substring(start + 1, end);
            String[] entries = rankingsStr.split("\\},\\s*\\{");
            for (String entry : entries) {
                entry = entry.replace("{", "").replace("}", "");
                String pos = extraerValorNumerico("{" + entry + "}", "position");
                String name = extraerValor("{" + entry + "}", "name");
                String points = extraerValorNumerico("{" + entry + "}", "points");
                System.out.println(pos + ". " + name + " - " + points + " pts");
            }
        }
        System.out.println("-----------------");
    }

    // =========================
    // Parsers simples
    // =========================
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

    private static String extraerValorNumerico(String json, String clave) {
        int idx = json.indexOf("\"" + clave + "\"");
        if (idx == -1) return null;
        idx = json.indexOf(":", idx);
        if (idx == -1) return null;
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        int inicio = idx;
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) idx++;
        return json.substring(inicio, idx);
    }
}
