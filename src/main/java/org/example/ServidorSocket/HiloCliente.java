package org.example.ServidorSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class HiloCliente implements Runnable {
    private Socket socketCliente;
    private PrintWriter salida;
    private BufferedReader entrada;
    private String nombre;

    public HiloCliente(Socket socketCliente) {
        this.socketCliente = socketCliente;
    }

    @Override
    public void run() {
        try {
            entrada = new BufferedReader(new InputStreamReader(socketCliente.getInputStream()));
            salida = new PrintWriter(socketCliente.getOutputStream(), true);

            // Leer y descartar cabeceras HTTP del cliente
            String lineaHTTP;
            while ((lineaHTTP = entrada.readLine()) != null && !lineaHTTP.isEmpty()) {
                // Descartamos las cabeceras HTTP (GET, Host, etc.)
            }

            // Solicitar nombre del jugador
            salida.println("NOMBRE:Introduce tu nombre de jugador:");
            nombre = entrada.readLine();

            if (nombre == null || nombre.trim().isEmpty()) {
                nombre = "Jugador" + System.currentTimeMillis() % 1000;
            }

            System.out.println("[+] " + nombre + " se ha unido al juego");
            salida.println("BIENVENIDO:Bienvenido " + nombre + "! Espera a que comience la pregunta...");
            Server.broadcast("INFO:" + nombre + " se ha unido al juego");

            String mensaje;
            while ((mensaje = entrada.readLine()) != null) {
                mensaje = mensaje.trim();

                if (mensaje.equalsIgnoreCase("/salir")) {
                    break;
                }

                // Procesar respuesta (solo si es A, B, C o D)
                if (mensaje.length() == 1) {
                    char respuesta = mensaje.toUpperCase().charAt(0);
                    if (respuesta >= 'A' && respuesta <= 'D') {
                        Server.recibirRespuesta(this, mensaje);
                    } else {
                        salida.println("ERROR:Respuesta invalida. Usa A, B, C o D");
                    }
                } else {
                    salida.println("ERROR:Envia solo una letra (A, B, C o D)");
                }
            }

        } catch (IOException e) {
            System.out.println("[!] Error con cliente " + nombre + ": " + e.getMessage());
        } finally {
            Server.removerCliente(this);
            try {
                socketCliente.close();
            } catch (IOException e) {
                System.out.println("[!] Error cerrando socket: " + e.getMessage());
            }
        }
    }

    public void enviarMensaje(String mensaje) {
        if (salida != null) {
            salida.println(mensaje);
        }
    }

    public String getNombre() {
        return nombre != null ? nombre : "Desconocido";
    }
}
