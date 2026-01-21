package org.example.Cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Cliente {

    private static final String HOST = "192.168.40.146"; // Cambiar a la IP del servidor si es necesario
    private static final int PUERTO = 8080;

    private Socket socket;
    private PrintWriter salida;
    private BufferedReader entrada;
    private Scanner scanner;
    private volatile boolean conectado = true;
    private volatile boolean esperandoRespuesta = false;

    public Cliente() {
        scanner = new Scanner(System.in);
    }

    public static void main(String[] args) {
        Cliente cliente = new Cliente();
        cliente.iniciar();
    }

    public void iniciar() {
        try {
            socket = new Socket(HOST, PUERTO);
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            salida = new PrintWriter(socket.getOutputStream(), true);

            System.out.println("╔════════════════════════════════════════╗");
            System.out.println("║       TRIVIA GAME - CONECTADO          ║");
            System.out.println("╚════════════════════════════════════════╝");

            // Hilo para escuchar mensajes del servidor
            Thread escuchador = new Thread(new EscuchadorServidor());
            escuchador.start();

            // Bucle principal para enviar respuestas
            while (conectado) {
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("/salir")) {
                    salida.println("/salir");
                    conectado = false;
                    break;
                }

                // Validar que sea A, B, C o D (solo 1 caracter)
                if (input.length() == 1) {
                    char respuesta = input.toUpperCase().charAt(0);
                    if (respuesta >= 'A' && respuesta <= 'D') {
                        salida.println(String.valueOf(respuesta));
                    } else {
                        System.out.println("[!] Respuesta invalida. Solo puedes usar A, B, C o D");
                    }
                } else if (!input.isEmpty()) {
                    System.out.println("[!] Envia solo UNA letra: A, B, C o D");
                }
            }

        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo conectar al servidor: " + e.getMessage());
        } finally {
            cerrarConexion();
        }
    }

    private void cerrarConexion() {
        try {
            conectado = false;
            if (scanner != null) scanner.close();
            if (salida != null) salida.close();
            if (entrada != null) entrada.close();
            if (socket != null) socket.close();
            System.out.println("\n[*] Desconectado del servidor");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class EscuchadorServidor implements Runnable {
        @Override
        public void run() {
            try {
                String mensaje;
                while (conectado && (mensaje = entrada.readLine()) != null) {
                    procesarMensaje(mensaje);
                }
            } catch (IOException error) {
                if (conectado) {
                    System.err.println("\n[!] Se perdio la conexion con el servidor");
                    conectado = false;
                }
            }
        }

        private void procesarMensaje(String mensaje) {
            if (mensaje.startsWith("NOMBRE:")) {
                System.out.println("\n" + mensaje.substring(7));
            } else if (mensaje.startsWith("BIENVENIDO:")) {
                System.out.println("\n" + mensaje.substring(11));
            } else if (mensaje.startsWith("PREGUNTA:")) {
                System.out.println("\n╔════════════════════════════════════════╗");
                System.out.println("║             PREGUNTA                   ║");
                System.out.println("╚════════════════════════════════════════╝");
                System.out.println("\n  " + mensaje.substring(9));
                esperandoRespuesta = true;
            } else if (mensaje.startsWith("OPCION_A:")) {
                System.out.println("  " + mensaje.substring(9));
            } else if (mensaje.startsWith("OPCION_B:")) {
                System.out.println("  " + mensaje.substring(9));
            } else if (mensaje.startsWith("OPCION_C:")) {
                System.out.println("  " + mensaje.substring(9));
            } else if (mensaje.startsWith("OPCION_D:")) {
                System.out.println("  " + mensaje.substring(9));
            } else if (mensaje.startsWith("RESPONDE:")) {
                System.out.println("\n>> Escribe tu respuesta (A, B, C o D):");
            } else if (mensaje.startsWith("RANKING:")) {
                String contenido = mensaje.substring(8);
                if (contenido.contains("===")) {
                    System.out.println("\n" + contenido);
                } else {
                    System.out.println("  " + contenido);
                }
                esperandoRespuesta = false;
            } else if (mensaje.startsWith("RECIBIDO:")) {
                System.out.println("[OK] " + mensaje.substring(9));
            } else if (mensaje.startsWith("ERROR:")) {
                System.out.println("[!] " + mensaje.substring(6));
            } else if (mensaje.startsWith("ESPERA:")) {
                System.out.println("[*] " + mensaje.substring(7));
            } else if (mensaje.startsWith("INFO:")) {
                System.out.println("[i] " + mensaje.substring(5));
            } else if (mensaje.equals("FIN_JUEGO")) {
                System.out.println("\n╔════════════════════════════════════════╗");
                System.out.println("║         JUEGO TERMINADO                ║");
                System.out.println("╚════════════════════════════════════════╝");
            } else {
                System.out.println(mensaje);
            }
        }
    }
}
