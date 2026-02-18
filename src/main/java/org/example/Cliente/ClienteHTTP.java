package org.example.Cliente;

import java.io.*;
import java.security.KeyStore;
import java.util.Scanner;
import javax.net.ssl.*;

public class ClienteHTTP {

    private static final String SERVER_IP = "localhost";
    private static final int SERVER_PORT = 7070;

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        // 1. Pedir nombre antes de conectar
        System.out.println("--- TRIVIA CLIENTE ---");
        System.out.print("IP del servidor (enter = localhost): ");
        String ip = scanner.nextLine().trim();
        if (ip.isEmpty()) ip = SERVER_IP;

        System.out.print("Introduce tu nombre de jugador: ");
        String miNombre = scanner.nextLine().trim();
        if (miNombre.isEmpty()) miNombre = "Jugador";

        // 2. Configuracion SSL
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream tsStream = ClienteHTTP.class.getResourceAsStream("/truststore.p12")) {
            ts.load(tsStream, "trivia123".toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        SSLSocketFactory sslFactory = sslContext.getSocketFactory();

        // 3. Conexion persistente
        try (SSLSocket socket = (SSLSocket) sslFactory.createSocket(ip, SERVER_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 4. Handshake HTTP con JSON de registro
            long idSesion = System.currentTimeMillis();

            out.println("POST /api/kahoot-like/join HTTP/1.1");
            out.println("Content-Type: application/json");
            out.println("");

            out.println("{");
            out.println("  \"action\": \"join_game\",");
            out.println("  \"playerId\": \"" + idSesion + "\",");
            out.println("  \"nickname\": \"" + miNombre + "\",");
            out.println("  \"client\": \"JavaConsole\"");
            out.println("}");

            final String nombreFinal = miNombre;

            // 5. Hilo listener: lee mensajes del servidor
            new Thread(() -> {
                try {
                    String fromServer;
                    while ((fromServer = in.readLine()) != null) {
                        if (fromServer.startsWith("NOMBRE:")) {
                            // El servidor pide el nombre, lo enviamos automaticamente
                            out.println(nombreFinal);
                        } else if (fromServer.startsWith("BIENVENIDO:")) {
                            System.out.println(">>> " + fromServer.substring(11));
                        } else if (fromServer.startsWith("INFO:")) {
                            System.out.println("[INFO] " + fromServer.substring(5));
                        } else if (fromServer.startsWith("PREGUNTA:")) {
                            System.out.println("\n=== PREGUNTA: " + fromServer.substring(9) + " ===");
                        } else if (fromServer.startsWith("OPCION_")) {
                            // OPCION_A:texto -> substring(9) salta "OPCION_X:"
                            System.out.println("   " + fromServer.substring(9));
                        } else if (fromServer.startsWith("RANKING:")) {
                            System.out.println(fromServer.substring(8));
                        } else if (fromServer.startsWith("RECIBIDO:")) {
                            System.out.println("[OK] " + fromServer.substring(9));
                        } else if (fromServer.startsWith("ERROR:")) {
                            System.out.println("[ERROR] " + fromServer.substring(6));
                        } else if (fromServer.startsWith("ESPERA:")) {
                            System.out.println("[ESPERA] " + fromServer.substring(7));
                        } else {
                            System.out.println(fromServer);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                }
            }).start();

            // 6. Hilo principal: enviar respuestas (A, B, C, D)
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
            System.err.println("No se pudo conectar al servidor.");
            e.printStackTrace();
        }
    }
}
