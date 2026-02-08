package org.example.Cliente;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

public class ClienteHTTP {

    private static final String SERVER = "http://localhost:8080";
    private static final HttpClient client = HttpClient.newHttpClient();

    private static String miId = null;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== TRIVIA CLIENTE ===");
        System.out.print("Tu nombre: ");
        String nombre = scanner.nextLine().trim();

        // Registrarse
        miId = registrarse(nombre);
        if (miId == null) {
            System.out.println("Error al conectar");
            return;
        }
        System.out.println("Conectado! ID: " + miId);
        System.out.println("\nComandos: VER (ver pregunta), A/B/C/D (responder), SALIR\n");

        // Bucle principal
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim().toUpperCase();

            if ("SALIR".equals(input)) break;

            if ("VER".equals(input)) {
                verPregunta();
            } else if (input.length() == 1 && input.charAt(0) >= 'A' && input.charAt(0) <= 'D') {
                enviarRespuesta(input);
            }
        }

        System.out.println("Adios!");
        scanner.close();
    }

    private static String registrarse(String nombre) {
        try {
            String json = "{\"nombre\": \"" + nombre + "\"}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER + "/registro"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return extraerValor(res.body(), "id");

        } catch (Exception e) {
            return null;
        }
    }

    private static void verPregunta() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER + "/pregunta"))
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body();

            if (body.contains("\"hay_pregunta\": false")) {
                System.out.println("No hay pregunta activa. Espera a que el admin envie una.");
                return;
            }

            String num = extraerValor(body, "numero");
            String texto = extraerValor(body, "texto");
            String[] opciones = extraerOpciones(body);

            System.out.println("\n--- PREGUNTA " + num + " ---");
            System.out.println(texto);
            for (String op : opciones) {
                System.out.println("  " + op);
            }
            System.out.println();

        } catch (Exception e) {
            System.out.println("Error al obtener pregunta");
        }
    }

    private static void enviarRespuesta(String respuesta) {
        try {
            String json = "{\"id\": \"" + miId + "\", \"respuesta\": \"" + respuesta + "\"}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SERVER + "/respuesta"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body();

            if (body.contains("\"ok\": true")) {
                String tiempo = extraerValor(body, "tiempo");
                boolean correcta = body.contains("\"correcta\": true");
                System.out.println(correcta ? "CORRECTO! (" + tiempo + "ms)" : "Incorrecto (" + tiempo + "ms)");
            } else {
                String error = extraerValor(body, "error");
                System.out.println("Error: " + error);
            }

        } catch (Exception e) {
            System.out.println("Error al enviar respuesta");
        }
    }

    private static String extraerValor(String json, String clave) {
        String buscar = "\"" + clave + "\":";
        int idx = json.indexOf(buscar);
        if (idx == -1) {
            buscar = "\"" + clave + "\": ";
            idx = json.indexOf(buscar);
        }
        if (idx == -1) return null;

        idx += buscar.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;

        if (json.charAt(idx) == '"') {
            int fin = json.indexOf("\"", idx + 1);
            return json.substring(idx + 1, fin);
        } else {
            int fin = idx;
            while (fin < json.length() && json.charAt(fin) != ',' && json.charAt(fin) != '}') fin++;
            return json.substring(idx, fin).trim();
        }
    }

    private static String[] extraerOpciones(String json) {
        int inicio = json.indexOf("[");
        int fin = json.indexOf("]");
        if (inicio == -1 || fin == -1) return new String[0];

        String arr = json.substring(inicio + 1, fin);
        String[] partes = arr.split("\",\\s*\"");
        String[] opciones = new String[partes.length];
        for (int i = 0; i < partes.length; i++) {
            opciones[i] = partes[i].replace("\"", "");
        }
        return opciones;
    }
}
