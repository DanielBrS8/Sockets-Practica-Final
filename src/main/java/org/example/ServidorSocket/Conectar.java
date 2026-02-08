package org.example.ServidorSocket;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.opencsv.CSVReader;

public class Conectar {

    private static final String SERVER = "35.222.249.194";
    private static final int PORT = 22;
    private static final String USER = "raidelmfernandez";
    private static final String PASS = "911gt3rs";
    private static final String REMOTE_FILE_PATH = "/home/raidelmfernandez/blooket.csv";

    public static List<Pregunta> descargarPreguntas() {
        List<Pregunta> preguntas = new ArrayList<>();
        Session session = null;
        ChannelSftp channelSftp = null;

        try {
            JSch jsch = new JSch();
            session = jsch.getSession(USER, SERVER, PORT);
            session.setPassword(PASS);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            System.out.println("[SFTP] Conectado al servidor SFTP");

            channelSftp = (ChannelSftp) session.openChannel("sftp");
            channelSftp.connect();

            try (InputStream input = channelSftp.get(REMOTE_FILE_PATH)) {
                preguntas = parsearCSV(input);
                System.out.println("[SFTP] Se cargaron " + preguntas.size() + " preguntas");
            }

        } catch (Exception ex) {
            System.err.println("[SFTP] Error: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            if (channelSftp != null && channelSftp.isConnected()) {
                channelSftp.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }

        return preguntas;
    }

    private static List<Pregunta> parsearCSV(InputStream input) throws Exception {
        List<Pregunta> preguntas = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(input, "UTF-8"))) {
            // Saltar fila 1 (BlooketImportTemplate) y fila 2 (headers)
            reader.readNext();
            reader.readNext();

            String[] linea;
            while ((linea = reader.readNext()) != null) {
                if (linea.length < 4) continue;

                String texto = linea[1].trim();
                String opcionA = linea[2].trim();
                String opcionB = linea[3].trim();
                String opcionC = linea.length > 4 ? linea[4].trim() : "";
                String opcionD = linea.length > 5 ? linea[5].trim() : "";

                // Columna 7: número de respuesta correcta (1=A, 2=B, 3=C, 4=D)
                char respuestaCorrecta = 'A';
                if (linea.length > 7 && !linea[7].trim().isEmpty()) {
                    int numRespuesta = Integer.parseInt(linea[7].trim());
                    respuestaCorrecta = (char) ('A' + numRespuesta - 1);
                }

                if (!texto.isEmpty()) {
                    preguntas.add(new Pregunta(texto, opcionA, opcionB, opcionC, opcionD, respuestaCorrecta));
                }
            }
        }

        return preguntas;
    }
}
