package org.example.ServidorSocket;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

    private static Set<HiloCliente> clientes = ConcurrentHashMap.newKeySet();
    private static List<Pregunta> preguntas = new ArrayList<>();
    private static int preguntaActual = 0;
    private static long tiempoInicioPregunta = 0;
    private static Map<HiloCliente, RespuestaCliente> respuestas = new ConcurrentHashMap<>();
    private static boolean preguntaActiva = false;

    public static void main(String[] args) {
        inicializarPreguntas();
        ExecutorService pooler = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            System.out.println("=== SERVIDOR TRIVIA INICIADO EN PUERTO 8080 ===");
            System.out.println("Comandos disponibles:");
            System.out.println("  NEXT - Enviar siguiente pregunta");
            System.out.println("  RANKING - Mostrar ranking actual");
            System.out.println("  RESET - Reiniciar preguntas");
            System.out.println("==========================================\n");

            // Hilo para leer comandos del servidor
            Thread comandos = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    String comando = scanner.nextLine().toUpperCase().trim();
                    procesarComandoServidor(comando);
                }
            });
            comandos.setDaemon(true);
            comandos.start();

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("[+] Cliente conectado desde: " + socket.getInetAddress());
                HiloCliente hilo = new HiloCliente(socket);
                clientes.add(hilo);
                pooler.execute(hilo);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            pooler.shutdown();
        }
    }

    private static void inicializarPreguntas() {
        preguntas.add(new Pregunta(
            "¿Cual es la capital de Francia?",
            "A) Londres", "B) Paris", "C) Madrid", "D) Berlin",
            'B'
        ));
        preguntas.add(new Pregunta(
            "¿Cuanto es 2 + 2?",
            "A) 3", "B) 5", "C) 4", "D) 22",
            'C'
        ));
        preguntas.add(new Pregunta(
            "¿Que lenguaje estamos usando?",
            "A) Python", "B) JavaScript", "C) C++", "D) Java",
            'D'
        ));
        preguntas.add(new Pregunta(
            "¿En que año se creo Java?",
            "A) 1995", "B) 2000", "C) 1990", "D) 2005",
            'A'
        ));
        preguntas.add(new Pregunta(
            "¿Cual es el planeta mas grande del sistema solar?",
            "A) Marte", "B) Saturno", "C) Jupiter", "D) Neptuno",
            'C'
        ));
    }

    private static void procesarComandoServidor(String comando) {
        switch (comando) {
            case "NEXT":
                enviarSiguientePregunta();
                break;
            case "RANKING":
                mostrarRankingConsola();
                break;
            case "RESET":
                preguntaActual = 0;
                System.out.println("[*] Preguntas reiniciadas");
                break;
            default:
                System.out.println("[!] Comando no reconocido: " + comando);
        }
    }

    private static void enviarSiguientePregunta() {
        if (clientes.isEmpty()) {
            System.out.println("[!] No hay clientes conectados");
            return;
        }

        if (preguntaActual >= preguntas.size()) {
            System.out.println("[!] No hay mas preguntas. Usa RESET para reiniciar.");
            broadcast("FIN_JUEGO");
            mostrarRankingFinal();
            return;
        }

        // Limpiar respuestas anteriores
        respuestas.clear();
        preguntaActiva = true;

        Pregunta p = preguntas.get(preguntaActual);
        tiempoInicioPregunta = System.currentTimeMillis();

        System.out.println("\n[PREGUNTA " + (preguntaActual + 1) + "/" + preguntas.size() + "] " + p.getTexto());
        System.out.println("Respuesta correcta: " + p.getRespuestaCorrecta());

        // Enviar pregunta a todos los clientes
        broadcast("PREGUNTA:" + p.getTexto());
        broadcast("OPCION_A:" + p.getOpcionA());
        broadcast("OPCION_B:" + p.getOpcionB());
        broadcast("OPCION_C:" + p.getOpcionC());
        broadcast("OPCION_D:" + p.getOpcionD());
        broadcast("RESPONDE:");

        preguntaActual++;
    }

    public static synchronized void recibirRespuesta(HiloCliente cliente, String respuesta) {
        if (!preguntaActiva) {
            cliente.enviarMensaje("ESPERA:Espera a la siguiente pregunta");
            return;
        }

        // Verificar que el cliente no haya respondido ya
        if (respuestas.containsKey(cliente)) {
            cliente.enviarMensaje("ERROR:Ya has respondido a esta pregunta");
            return;
        }

        // Convertir a mayuscula
        char respuestaChar = respuesta.toUpperCase().charAt(0);
        long tiempoRespuesta = System.currentTimeMillis() - tiempoInicioPregunta;

        Pregunta preguntaAnterior = preguntas.get(preguntaActual - 1);
        boolean correcta = (respuestaChar == preguntaAnterior.getRespuestaCorrecta());

        RespuestaCliente rc = new RespuestaCliente(cliente.getNombre(), respuestaChar, tiempoRespuesta, correcta);
        respuestas.put(cliente, rc);

        // Mostrar en terminal del servidor
        System.out.println("[RESPUESTA] " + cliente.getNombre() + " -> " + respuestaChar +
                          " (" + tiempoRespuesta + "ms) - " + (correcta ? "CORRECTA" : "INCORRECTA"));

        cliente.enviarMensaje("RECIBIDO:Respuesta " + respuestaChar + " recibida en " + tiempoRespuesta + "ms");

        // Verificar si todos respondieron
        if (respuestas.size() == clientes.size()) {
            preguntaActiva = false;
            enviarRanking();
        }
    }

    private static void enviarRanking() {
        System.out.println("\n=== RANKING DE LA PREGUNTA ===");

        List<RespuestaCliente> ranking = new ArrayList<>(respuestas.values());
        // Ordenar: primero correctas, luego por tiempo
        ranking.sort((a, b) -> {
            if (a.isCorrecta() && !b.isCorrecta()) return -1;
            if (!a.isCorrecta() && b.isCorrecta()) return 1;
            return Long.compare(a.getTiempoMs(), b.getTiempoMs());
        });

        broadcast("RANKING:=== RANKING ===");
        int posicion = 1;
        for (RespuestaCliente rc : ranking) {
            String linea = posicion + ". " + rc.getNombre() + " - " + rc.getRespuesta() +
                          " (" + rc.getTiempoMs() + "ms) " + (rc.isCorrecta() ? "CORRECTO" : "INCORRECTO");
            System.out.println(linea);
            broadcast("RANKING:" + linea);
            posicion++;
        }
        broadcast("RANKING:==================");
        System.out.println("==============================\n");
    }

    private static void mostrarRankingConsola() {
        if (respuestas.isEmpty()) {
            System.out.println("[!] No hay respuestas aun");
            return;
        }
        System.out.println("\n=== RANKING ACTUAL ===");
        for (RespuestaCliente rc : respuestas.values()) {
            System.out.println(rc.getNombre() + " - " + rc.getRespuesta() + " (" + rc.getTiempoMs() + "ms)");
        }
        System.out.println("======================\n");
    }

    private static void mostrarRankingFinal() {
        broadcast("RANKING:=== JUEGO TERMINADO ===");
        System.out.println("\n=== JUEGO TERMINADO ===");
    }

    public static void broadcast(String mensaje) {
        for (HiloCliente cliente : clientes) {
            cliente.enviarMensaje(mensaje);
        }
    }

    public static void removerCliente(HiloCliente cliente) {
        clientes.remove(cliente);
        respuestas.remove(cliente);
        System.out.println("[-] Cliente desconectado: " + cliente.getNombre());
    }

    public static boolean isPreguntaActiva() {
        return preguntaActiva;
    }
}

