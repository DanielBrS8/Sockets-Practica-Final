package org.example.Cliente;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Scanner;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class ClienteHTTP {
    private static final String SERVER_IP = "localhost"; // Cambiar si es otra PC
    private static final int SERVER_PORT = 8080;

    public static void main(String[] args) {

        // --- CONFIGURACIÓN SSL (SEGURIDAD) ---
        // Indicamos dónde está el certificado en el que confiamos (el mismo que
        // creaste)
        System.setProperty("javax.net.ssl.trustStore", "client.truststore");
        System.setProperty("javax.net.ssl.trustStorePassword", "trivia123");

        SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        // -------------------------------------

        try (SSLSocket socket = (SSLSocket) sslFactory.createSocket(SERVER_IP, SERVER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                Scanner scanner = new Scanner(System.in)) {

            // Enviar cabecera HTTP al servidor
            out.println("GET /trivia HTTP/1.1");
            out.println("Host: " + SERVER_IP);
            out.println("");

            // Hilo para escuchar al servidor
            new Thread(() -> {
                try {
                    String fromServer;
                    while ((fromServer = in.readLine()) != null) {
                        if (fromServer.startsWith("NOMBRE:")) {
                            System.out.println(fromServer.substring(7));
                        } else if (fromServer.startsWith("BIENVENIDO:")) {
                            System.out.println(">>> " + fromServer.substring(11));
                        } else if (fromServer.startsWith("INFO:")) {
                            System.out.println("[INFO] " + fromServer.substring(5));
                        } else if (fromServer.startsWith("PREGUNTA:")) {
                            System.out.println("\n¿? PREGUNTA: " + fromServer.substring(9));
                        } else if (fromServer.startsWith("OPCION_")) {
                            char letra = fromServer.charAt(7);
                            System.out.println("   " + letra + ") " + fromServer.substring(9));
                        } else if (fromServer.startsWith("RANKING:")) {
                            System.out.println(fromServer.substring(8));
                        } else if (fromServer.startsWith("RECIBIDO:") || fromServer.startsWith("ERROR:")
                                || fromServer.startsWith("ESPERA:")) {
                            System.out.println(fromServer);
                        } else {
                            // Cualquier otro mensaje
                            System.out.println(fromServer);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Desconectado del servidor.");
                }
            }).start();

            // Hilo principal (ESTO NO CAMBIA)
            while (true) {
                if (scanner.hasNextLine()) {
                    String userInput = scanner.nextLine();
                    out.println(userInput);
                    if (userInput.equalsIgnoreCase("/salir")) {
                        break;
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("No se pudo conectar al servidor seguro (SSL).");
            e.printStackTrace(); // Muestra el error exacto si falla el certificado
        }
    }
}