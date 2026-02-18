package org.example.ServidorHTTP;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.*;

public class ServidorHTTP {

    private static final int PUERTO = 7070;

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

        // Cargar keystore con el certificado TLS
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream ksStream = ServidorHTTP.class.getResourceAsStream("/keystore.p12")) {
            ks.load(ksStream, "trivia123".toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "trivia123".toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        ServerSocket serverSocket = factory.createServerSocket(PUERTO);

        ExecutorService pool = Executors.newFixedThreadPool(10);

        System.out.println("=================================");
        System.out.println("  SERVIDOR TRIVIA - Puerto " + PUERTO);
        System.out.println("  (TLS + Conexion persistente)");
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
                    broadcast("INFO:Juego reiniciado. Esperando nueva partida...");
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

            // 1. Leer handshake HTTP (POST + cabeceras hasta linea vacia)
            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Leer cabeceras hasta linea vacia
            String linea;
            while ((linea = in.readLine()) != null && !linea.isEmpty()) {
                // solo consumimos las cabeceras
            }

            // 2. Leer body JSON (lineas hasta cerrar llave)
            StringBuilder jsonBody = new StringBuilder();
            while ((linea = in.readLine()) != null) {
                jsonBody.append(linea);
                if (linea.trim().equals("}")) break;
            }

            // 3. Extraer nickname del JSON
            String nickname = extraerValor(jsonBody.toString(), "nickname");

            // 4. Pedir nombre (protocolo NOMBRE:)
            out.println("NOMBRE:Introduce tu nombre");
            String nombreRecibido = in.readLine();
            if (nombreRecibido != null && !nombreRecibido.trim().isEmpty()) {
                nickname = nombreRecibido.trim();
            }
            if (nickname == null || nickname.isEmpty()) {
                nickname = "Jugador" + System.currentTimeMillis() % 1000;
            }

            // 5. Registrar jugador
            jugadorId = UUID.randomUUID().toString().substring(0, 8);
            jugadores.put(jugadorId, nickname);
            puntuaciones.put(jugadorId, 0);
            clientesConectados.put(jugadorId, out);

            System.out.println("[+] " + nickname + " conectado (ID: " + jugadorId + ")");

            // 6. Enviar bienvenida e info
            out.println("BIENVENIDO:Bienvenido al Trivia, " + nickname + "!");
            out.println("INFO:Jugadores conectados: " + clientesConectados.size());
            out.println("INFO:Esperando a que el host lance una pregunta...");

            // Notificar a todos que se unio un jugador
            broadcast("INFO:" + nickname + " se ha unido! (" + clientesConectados.size() + " jugadores)");

            // 7. Bucle de lectura: escuchar respuestas del cliente
            String mensaje;
            while ((mensaje = in.readLine()) != null) {
                mensaje = mensaje.trim();

                if (mensaje.equalsIgnoreCase("/salir")) {
                    out.println("INFO:Hasta luego!");
                    break;
                }

                // Procesar respuesta A/B/C/D
                if (mensaje.length() == 1) {
                    char resp = mensaje.toUpperCase().charAt(0);
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
            // Cliente desconectado
        } finally {
            // Limpiar al desconectar
            if (jugadorId != null) {
                String nombre = jugadores.get(jugadorId);
                clientesConectados.remove(jugadorId);
                System.out.println("[-] " + (nombre != null ? nombre : jugadorId) + " desconectado");
                broadcast("INFO:" + (nombre != null ? nombre : "Un jugador") + " se ha desconectado. (" + clientesConectados.size() + " jugadores)");
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
            System.out.println("[PREGUNTA " + (preguntaActual + 1) + "/" + preguntas.size() + "] " + p.getTexto());

            broadcast("PREGUNTA:" + p.getTexto());
            broadcast("OPCION_A:" + p.getOpcionA());
            broadcast("OPCION_B:" + p.getOpcionB());
            broadcast("OPCION_C:" + p.getOpcionC());
            broadcast("OPCION_D:" + p.getOpcionD());
            broadcast("INFO:Pregunta " + (preguntaActual + 1) + " de " + preguntas.size() + " - Responde con A, B, C o D");
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
        boolean correcta = respuesta == preguntas.get(preguntaActual).getRespuestaCorrecta();

        RespuestaDTO dto = new RespuestaDTO(nombre, respuesta, tiempo, correcta, null);
        respuestas.put(id, dto);

        if (correcta) {
            int puntos = Math.max(100, 1000 - (int)(tiempo / 10));
            puntuaciones.merge(id, puntos, Integer::sum);
            out.println("RECIBIDO:Correcto! +" + puntos + " puntos (" + tiempo + "ms)");
        } else {
            out.println("RECIBIDO:Incorrecto. La respuesta era " + preguntas.get(preguntaActual).getRespuestaCorrecta() + " (" + tiempo + "ms)");
        }

        System.out.println("[R] " + nombre + " -> " + respuesta + " (" + tiempo + "ms) " + (correcta ? "OK" : "X"));

        // Si todos respondieron, mostrar ranking automaticamente
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
        for (Map.Entry<String, Integer> entry : ranking) {
            String nombre = jugadores.get(entry.getKey());
            if (nombre != null) {
                broadcast("RANKING:" + pos + ". " + nombre + " - " + entry.getValue() + " pts");
                pos++;
            }
        }
        broadcast("RANKING:-----------------");
    }

    // === Broadcast a todos los clientes ===

    private static void broadcast(String mensaje) {
        for (PrintWriter writer : clientesConectados.values()) {
            writer.println(mensaje);
        }
    }

    // === Utilidades ===

    private static void inicializarPreguntas() {
        preguntas.add(new Pregunta("Capital de Francia?", "A) Londres", "B) Paris", "C) Madrid", "D) Berlin", 'B'));
        preguntas.add(new Pregunta("Cuanto es 2+2?", "A) 3", "B) 5", "C) 4", "D) 22", 'C'));
        preguntas.add(new Pregunta("Lenguaje de este proyecto?", "A) Python", "B) JavaScript", "C) C++", "D) Java", 'D'));
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
