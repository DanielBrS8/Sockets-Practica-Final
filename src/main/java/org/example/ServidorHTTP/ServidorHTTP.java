package org.example.ServidorHTTP;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.*;;

public class ServidorHTTP {

    private static final int PUERTO = 7070;

    // Jugadores: id -> nombre
    private static Map<String, String> jugadores = new ConcurrentHashMap<>();

    // Preguntas del juego
    private static List<Pregunta> preguntas = new ArrayList<>();
    private static int preguntaActual = -1;
    private static long tiempoInicio = 0;

    // Respuestas: jugadorId -> RespuestaDTO
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
        System.out.println("  (TLS + HTTP manual)");
        System.out.println("=================================");
        System.out.println("Comandos: NEXT, RESET");

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
            if ("NEXT".equals(cmd)) {
                siguientePregunta();
            } else if ("RESET".equals(cmd)) {
                preguntaActual = -1;
                respuestas.clear();
                System.out.println("[*] Reiniciado");
            }
        }
    }

    private static void manejarConexion(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            // Leer linea de peticion (ej: "GET /pregunta HTTP/1.1")
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] partes = requestLine.split(" ");
            if (partes.length < 2) return;

            String metodo = partes[0];
            String ruta = partes[1];

            // Leer cabeceras
            int contentLength = 0;
            String linea;
            while ((linea = in.readLine()) != null && !linea.isEmpty()) {
                if (linea.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(linea.substring(15).trim());
                }
            }

            // Leer body si existe
            String body = "";
            if (contentLength > 0) {
                char[] buffer = new char[contentLength];
                int leidos = 0;
                while (leidos < contentLength) {
                    int r = in.read(buffer, leidos, contentLength - leidos);
                    if (r == -1) break;
                    leidos += r;
                }
                body = new String(buffer, 0, leidos);
            }

            // Enrutar la peticion
            String jsonRespuesta;
            int codigo;

            switch (ruta) {
                case "/registro":
                    if (!"POST".equals(metodo)) {
                        codigo = 405;
                        jsonRespuesta = "{\"error\": \"Metodo no permitido\"}";
                    } else {
                        String[] resultado = RegistroHandler.procesar(body);
                        codigo = Integer.parseInt(resultado[0]);
                        jsonRespuesta = resultado[1];
                    }
                    break;

                case "/pregunta":
                    if (!"GET".equals(metodo)) {
                        codigo = 405;
                        jsonRespuesta = "{\"error\": \"Metodo no permitido\"}";
                    } else {
                        String[] resultado = PreguntaHandler.procesar();
                        codigo = Integer.parseInt(resultado[0]);
                        jsonRespuesta = resultado[1];
                    }
                    break;

                case "/respuesta":
                    if (!"POST".equals(metodo)) {
                        codigo = 405;
                        jsonRespuesta = "{\"error\": \"Metodo no permitido\"}";
                    } else {
                        String[] resultado = RespuestaHandler.procesar(body);
                        codigo = Integer.parseInt(resultado[0]);
                        jsonRespuesta = resultado[1];
                    }
                    break;

                default:
                    codigo = 404;
                    jsonRespuesta = "{\"error\": \"Ruta no encontrada\"}";
                    break;
            }

            // Enviar respuesta HTTP
            enviarRespuestaHTTP(out, codigo, jsonRespuesta);

        } catch (IOException e) {
            System.out.println("[ERROR] Manejando conexion: " + e.getMessage());
        }
    }

    private static void enviarRespuestaHTTP(OutputStream out, int codigo, String json) throws IOException {
        String status = switch (codigo) {
            case 200 -> "200 OK";
            case 400 -> "400 Bad Request";
            case 404 -> "404 Not Found";
            case 405 -> "405 Method Not Allowed";
            default -> codigo + " Error";
        };

        byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);

        String respuesta = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        out.write(respuesta.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private static void inicializarPreguntas() {
        preguntas.add(new Pregunta("¿Capital de Francia?", "A) Londres", "B) Paris", "C) Madrid", "D) Berlin", 'B'));
        preguntas.add(new Pregunta("¿Cuanto es 2+2?", "A) 3", "B) 5", "C) 4", "D) 22", 'C'));
        preguntas.add(new Pregunta("¿Lenguaje de este proyecto?", "A) Python", "B) JavaScript", "C) C++", "D) Java", 'D'));
    }

    private static void siguientePregunta() {
        preguntaActual++;
        respuestas.clear();
        tiempoInicio = System.currentTimeMillis();

        if (preguntaActual < preguntas.size()) {
            System.out.println("[PREGUNTA " + (preguntaActual + 1) + "] " + preguntas.get(preguntaActual).getTexto());
        } else {
            System.out.println("[!] No hay mas preguntas");
        }
    }

    // === Metodos para los Handlers ===

    public static String registrarJugador(String nombre) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        jugadores.put(id, nombre);
        System.out.println("[+] " + nombre + " registrado (ID: " + id + ")");
        return id;
    }

    public static Pregunta getPreguntaActual() {
        if (preguntaActual >= 0 && preguntaActual < preguntas.size()) {
            return preguntas.get(preguntaActual);
        }
        return null;
    }

    public static int getNumeroPregunta() {
        return preguntaActual + 1;
    }

    public static int getTotalPreguntas() {
        return preguntas.size();
    }

    public static RespuestaDTO procesarRespuesta(String id, char respuesta) {
        String nombre = jugadores.get(id);
        if (nombre == null) return null;

        if (preguntaActual < 0 || preguntaActual >= preguntas.size()) {
            return new RespuestaDTO(nombre, respuesta, 0, false, "No hay pregunta activa");
        }

        if (respuestas.containsKey(id)) {
            return new RespuestaDTO(nombre, respuesta, 0, false, "Ya respondiste");
        }

        long tiempo = System.currentTimeMillis() - tiempoInicio;
        boolean correcta = respuesta == preguntas.get(preguntaActual).getRespuestaCorrecta();

        RespuestaDTO dto = new RespuestaDTO(nombre, respuesta, tiempo, correcta, null);
        respuestas.put(id, dto);

        System.out.println("[R] " + nombre + " -> " + respuesta + " (" + tiempo + "ms) " + (correcta ? "OK" : "X"));
        return dto;
    }
}
