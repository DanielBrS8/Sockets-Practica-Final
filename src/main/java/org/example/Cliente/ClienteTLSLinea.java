package org.example.Cliente;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Scanner;

public class ClienteTLSLinea {

    private static final String DEFAULT_IP = "localhost";
    private static final int DEFAULT_PORT = 7070;

    // Truststore que contiene el CERTIFICADO del servidor al que te conectas (tu
    // amigo)
    private static final String TRUSTSTORE_PATH = "cert-raidel/server-truststore.p12";
    private static final char[] TRUSTSTORE_PASS = "trivia123".toCharArray(); // <-- pon la correcta

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        System.out.println("--- TRIVIA CLIENTE (TLS + Lineas) ---");
        System.out.print("IP del servidor (enter = localhost): ");
        String ip = scanner.nextLine().trim();
        if (ip.isEmpty())
            ip = DEFAULT_IP;

        System.out.print("Puerto (enter = 7070): ");
        String p = scanner.nextLine().trim();
        int port = p.isEmpty() ? DEFAULT_PORT : Integer.parseInt(p);

        System.out.print("Introduce tu nombre de jugador: ");
        String miNombre = scanner.nextLine().trim();
        if (miNombre.isEmpty())
            miNombre = "Jugador";

        try (SSLSocket socket = crearSSLSocket(ip, port);
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            // 1) JOIN: mismo formato que el cliente de tu amigo (JSON multilinea, termina
            // en una linea "}")
            enviarJoinComoLinea(out, miNombre);

            // 2) Listener: lee lineas tipo INFO:, PREGUNTA:, OPCION_A:, etc.
            String nombreFinal = miNombre;
            Thread listener = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("NOMBRE:")) {
                            // El servidor pide nombre => respondemos
                            out.println(nombreFinal);
                        } else if (line.startsWith("BIENVENIDO:")) {
                            System.out.println(">>> " + line.substring("BIENVENIDO:".length()));
                        } else if (line.startsWith("INFO:")) {
                            System.out.println("[INFO] " + line.substring("INFO:".length()));
                        } else if (line.startsWith("PREGUNTA:")) {
                            System.out.println("\n=== PREGUNTA: " + line.substring("PREGUNTA:".length()) + " ===");
                        } else if (line.startsWith("OPCION_")) {
                            // Ej: OPCION_A:Texto -> mostramos "A:Texto" (igual que tu amigo)
                            System.out.println("   " + line.substring(9));
                        } else if (line.startsWith("RANKING:")) {
                            System.out.println(line.substring("RANKING:".length()));
                        } else if (line.startsWith("RECIBIDO:")) {
                            System.out.println("[OK] " + line.substring("RECIBIDO:".length()));
                        } else if (line.startsWith("ERROR:")) {
                            System.out.println("[ERROR] " + line.substring("ERROR:".length()));
                        } else if (line.startsWith("ESPERA:")) {
                            System.out.println("[ESPERA] " + line.substring("ESPERA:".length()));
                        } else {
                            System.out.println(line);
                        }
                    }
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                } catch (IOException e) {
                    System.out.println("Desconectado del servidor.");
                    System.exit(0);
                }
            });
            listener.setDaemon(true);
            listener.start();

            // 3) Enviar respuestas como lineas (A/B/C/D o /salir)
            while (scanner.hasNextLine()) {
                String userInput = scanner.nextLine().trim();
                out.println(userInput);

                if (userInput.equalsIgnoreCase("/salir")) {
                    break;
                }
            }

        } catch (Exception e) {
            System.err.println("No se pudo conectar al servidor.");
            e.printStackTrace();
        }
    }

    private static void enviarJoinComoLinea(PrintWriter out, String miNombre) {
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
        out.flush();
    }

    private static SSLSocket crearSSLSocket(String host, int port) throws Exception {
        // Cargar truststore del servidor al que te conectas
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Paths.get(TRUSTSTORE_PATH))) {
            ts.load(is, TRUSTSTORE_PASS);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        SSLSocketFactory factory = ctx.getSocketFactory();

        // Conectar con timeout para evitar “colgados”
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(15000);

        socket.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
        socket.startHandshake();

        System.out.println("TLS OK -> " + socket.getSession().getProtocol());
        return socket;
    }
}
