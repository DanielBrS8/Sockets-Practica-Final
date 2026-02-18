package org.example.Cliente;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class ClienteHTTP {

    private static final String SERVER_IP = "localhost";
    private static final int SERVER_PORT = 8080;

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        // 1. Pedir nombre antes de conectar
        System.out.println("--- TRIVIA CLIENTE ---");
        System.out.print("IP del servidor (enter = localhost): ");
        String ip = scanner.nextLine().trim();
        if (ip.isEmpty())
            ip = SERVER_IP;

        System.out.print("Introduce tu nombre de jugador: ");
        String miNombre = scanner.nextLine().trim();
        if (miNombre.isEmpty())
            miNombre = "Jugador";

        // 2. Conexion TCP
        try (Socket socket = new Socket(ip, SERVER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 3. Enviar peticion HTTP de join
            enviarPeticionHTTP(out, "POST", "/api/kahoot-like/join",
                "{\"action\": \"join_game\", " +
                "\"playerId\": \"" + System.currentTimeMillis() + "\", " +
                "\"nickname\": \"" + miNombre + "\", " +
                "\"client\": \"JavaConsole\"}");

            // 4. Hilo listener: lee mensajes HTTP del servidor
            new Thread(() -> {
                try {
                    while (true) {
                        String[] mensaje = leerMensajeHTTP(in);
                        if (mensaje == null) break;

                        String firstLine = mensaje[0];
                        String body = mensaje[1];

                        // Es una peticion del servidor (pregunta, info, ranking)
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
                        // Es una respuesta HTTP del servidor (resultado, bienvenida, error)
                        else if (firstLine.startsWith("HTTP/1.1")) {
                            String type = extraerValor(body, "type");
                            if (type != null) {
                                switch (type) {
                                    case "welcome":
                                        System.out.println(">>> " + extraerValor(body, "message"));
                                        System.out.println("[INFO] Esperando a que el host lance una pregunta...");
                                        break;
                                    case "result":
                                        mostrarResultado(body);
                                        break;
                                    case "wait":
                                        System.out.println("[ESPERA] " + extraerValor(body, "message"));
                                        break;
                                }
                            }
                            String error = extraerValor(body, "error");
                            if (error != null) {
                                System.out.println("[ERROR] " + error);
                            }
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                }
            }).start();

            // 5. Hilo principal: enviar respuestas
            while (true) {
                if (scanner.hasNextLine()) {
                    String userInput = scanner.nextLine().trim();

                    if (userInput.equalsIgnoreCase("/salir")) {
                        enviarPeticionHTTP(out, "POST", "/api/kahoot-like/quit",
                            "{\"action\": \"quit\"}");
                        break;
                    }

                    // Validar que solo se envie A, B, C o D
                    if (userInput.length() == 1) {
                        char c = userInput.toUpperCase().charAt(0);
                        if (c >= 'A' && c <= 'D') {
                            enviarPeticionHTTP(out, "POST", "/api/kahoot-like/answer",
                                "{\"answer\": \"" + c + "\"}");
                        } else {
                            System.out.println("[!] Solo puedes responder A, B, C o D");
                        }
                    } else {
                        System.out.println("[!] Solo puedes responder A, B, C o D (o /salir)");
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("No se pudo conectar al servidor.");
            e.printStackTrace();
        }
    }

    // === Mostrar datos en consola ===

    private static void mostrarPregunta(String json) {
        String question = extraerValor(json, "question");
        System.out.println("\n=== PREGUNTA: " + question + " ===");

        // Extraer choices del JSON (simple parser)
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
        boolean correct = body.contains("\"correct\": true");
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
        // Extraer rankings array y mostrar
        int start = body.indexOf("[");
        int end = body.lastIndexOf("]");
        if (start != -1 && end != -1) {
            String rankingsStr = body.substring(start + 1, end);
            // Separar por objetos JSON
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

    // === Envio/Lectura HTTP ===

    private static void enviarPeticionHTTP(PrintWriter out, String method, String path, String jsonBody) {
        out.println(method + " " + path + " HTTP/1.1");
        out.println("Content-Type: application/json");
        out.println("");
        out.println(jsonBody);
    }

    // Leer un mensaje HTTP (puede ser peticion POST o respuesta HTTP/1.1)
    private static String[] leerMensajeHTTP(BufferedReader in) throws IOException {
        // 1. Leer primera linea (request line o status line)
        String firstLine = in.readLine();
        if (firstLine == null) return null;

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

        return new String[]{firstLine, jsonBody.toString()};
    }

    // === Utilidades ===

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
        idx++; // saltar ':'
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        int inicio = idx;
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) idx++;
        return json.substring(inicio, idx);
    }
}
