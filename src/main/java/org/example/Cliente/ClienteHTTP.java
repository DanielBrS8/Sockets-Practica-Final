package org.example.Cliente;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

public class ClienteHTTP {

    private static String SERVER_URL;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static String jugadorId = null;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Pedir IP del servidor
        System.out.print("IP del servidor (ej: 192.168.1.100): ");
        String ip = scanner.nextLine().trim();
        if (ip.isEmpty()) ip = "localhost";
        SERVER_URL = "http://" + ip + ":7070";

        System.out.println("================================");
        System.out.println("  TRIVIA CLIENT - " + SERVER_URL);
        System.out.println("================================");

        // Registro
        System.out.print("Tu nombre: ");
        String nombre = scanner.nextLine().trim();
        if (nombre.isEmpty()) nombre = "Jugador";

        String regBody = String.format("{\"nombre\": \"%s\"}", nombre);
        String regResp = post("/registro", regBody);
        if (regResp == null) {
            System.out.println("[ERROR] No se pudo conectar al servidor");
            scanner.close();
            return;
        }
        System.out.println("[*] Servidor: " + regResp);
        jugadorId = extraerValor(regResp, "id");

        if (jugadorId == null) {
            System.out.println("[ERROR] No se pudo registrar");
            scanner.close();
            return;
        }
        System.out.println("[OK] Registrado con ID: " + jugadorId);
        System.out.println();
        System.out.println("Comandos: POLL (ver pregunta), A/B/C/D (responder), SALIR");
        System.out.println();

        // Bucle principal
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim().toUpperCase();

            if ("SALIR".equals(input)) {
                System.out.println("Adios!");
                break;
            } else if ("POLL".equals(input)) {
                consultarPregunta();
            } else if (input.length() == 1 && input.charAt(0) >= 'A' && input.charAt(0) <= 'D') {
                enviarRespuesta(input);
            } else {
                System.out.println("[!] Comandos: POLL, A, B, C, D, SALIR");
            }
        }

        scanner.close();
    }

    private static void consultarPregunta() {
        String resp = get("/pregunta");
        if (resp == null) {
            System.out.println("[ERROR] No se pudo conectar");
            return;
        }

        if (resp.contains("\"hay_pregunta\": false")) {
            System.out.println("[*] No hay pregunta activa. Espera a que el servidor lance una (NEXT).");
            return;
        }

        String texto = extraerValor(resp, "texto");
        String numero = extraerValor(resp, "numero");
        String total = extraerValor(resp, "total");

        System.out.println();
        System.out.println("=== PREGUNTA " + numero + "/" + total + " ===");
        System.out.println(texto);

        // Extraer opciones del array
        int idx = resp.indexOf("\"opciones\"");
        if (idx != -1) {
            String opciones = resp.substring(idx);
            String[] letras = {"A", "B", "C", "D"};
            int pos = 0;
            for (String letra : letras) {
                int start = opciones.indexOf("\"", pos + 1);
                if (start == -1) break;
                // Skip if we hit a bracket or colon area
                while (start != -1 && (opciones.charAt(start - 1) == '[' || opciones.charAt(start - 1) == ',')) {
                    start = opciones.indexOf("\"", pos + 1);
                    break;
                }
                start = opciones.indexOf("\"", pos);
                if (start == -1) break;
                int end = opciones.indexOf("\"", start + 1);
                if (end == -1) break;
                System.out.println("  " + letra + ") " + opciones.substring(start + 1, end));
                pos = end + 1;
            }
        }
        System.out.println();
        System.out.println("Responde con A, B, C o D:");
    }

    private static void enviarRespuesta(String respuesta) {
        if (jugadorId == null) {
            System.out.println("[ERROR] No estas registrado");
            return;
        }

        String body = String.format("{\"id\": \"%s\", \"respuesta\": \"%s\"}", jugadorId, respuesta);
        String resp = post("/respuesta", body);

        if (resp == null) {
            System.out.println("[ERROR] No se pudo conectar");
            return;
        }

        if (resp.contains("\"correcta\": true")) {
            String tiempo = extraerValor(resp, "tiempo");
            System.out.println("[CORRECTO] Tiempo: " + tiempo + "ms");
        } else if (resp.contains("\"correcta\": false")) {
            String tiempo = extraerValor(resp, "tiempo");
            System.out.println("[INCORRECTO] Tiempo: " + tiempo + "ms");
        } else {
            System.out.println("[*] " + resp);
        }
    }

    private static String get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER_URL + path))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String post(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER_URL + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String extraerValor(String json, String clave) {
        // Handle numeric values
        int idx = json.indexOf("\"" + clave + "\"");
        if (idx == -1) return null;
        idx = json.indexOf(":", idx);
        if (idx == -1) return null;
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;

        if (json.charAt(idx) == '"') {
            int fin = json.indexOf("\"", idx + 1);
            if (fin == -1) return null;
            return json.substring(idx + 1, fin);
        } else {
            int fin = idx;
            while (fin < json.length() && json.charAt(fin) != ',' && json.charAt(fin) != '}') fin++;
            return json.substring(idx, fin).trim();
        }
    }
}
