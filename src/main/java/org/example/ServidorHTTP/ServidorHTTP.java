package org.example.ServidorHTTP;

import com.sun.net.httpserver.HttpServer;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

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

    public static void main(String[] args) throws IOException {
        inicializarPreguntas();

        HttpServer server = HttpServer.create(new InetSocketAddress(PUERTO), 0);

        server.createContext("/registro", new RegistroHandler());
        server.createContext("/pregunta", new PreguntaHandler());
        server.createContext("/respuesta", new RespuestaHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("=================================");
        System.out.println("  SERVIDOR TRIVIA - Puerto " + PUERTO);
        System.out.println("=================================");
        System.out.println("Comandos: NEXT, RESET");

        // Comandos de consola
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

    // === Métodos para los Handlers ===

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
