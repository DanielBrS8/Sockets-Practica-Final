# Documentación de Cambios — Servidor Trivia con SSL/TLS y FTP

## Módulo: Programación de Servicios y Procesos (PSP)
### Ciclo Formativo: Desarrollo de Aplicaciones Multiplataforma (DAM)

---

## 1. Objetivo de los cambios

Partimos de un proyecto de trivia multijugador basado en sockets TCP donde:

- El servidor no tenía preguntas cargadas (el método `inicializarPreguntas()` estaba vacío).
- La clase `Conectar.java` se conectaba a un servidor FTP ajeno pero no procesaba el archivo CSV.
- Toda la comunicación entre servidor y clientes viajaba en **texto plano**, sin cifrado.

Los cambios implementados persiguen tres objetivos:

1. **Conectar a un servidor FTP propio** para descargar un archivo CSV con formato Blooket.
2. **Parsear el CSV** y generar automáticamente las preguntas del juego.
3. **Cifrar la comunicación del juego** con SSL/TLS usando `SSLServerSocket` y `SSLSocket`.

---

## 2. Conceptos teóricos aplicados

### 2.1. SSL/TLS en Java

SSL (Secure Sockets Layer) y su sucesor TLS (Transport Layer Security) son protocolos criptográficos que proporcionan **confidencialidad** e **integridad** en las comunicaciones de red.

En Java, el paquete `javax.net.ssl` nos ofrece las clases necesarias:

| Clase | Función |
|---|---|
| `SSLContext` | Contexto de seguridad que configura el protocolo TLS |
| `SSLServerSocket` | Socket servidor que acepta conexiones cifradas |
| `SSLSocket` | Socket cliente que establece conexiones cifradas |
| `SSLServerSocketFactory` | Fábrica que crea `SSLServerSocket` configurados |
| `SSLSocketFactory` | Fábrica que crea `SSLSocket` configurados |
| `KeyManagerFactory` | Gestiona las claves privadas del servidor (keystore) |
| `TrustManagerFactory` | Gestiona los certificados de confianza del cliente (truststore) |

**Flujo del handshake TLS:**

```
Cliente                          Servidor
   |--- ClientHello --------------->|
   |<-- ServerHello + Certificado --|
   |--- Verificación del cert. ---->|
   |<-- Intercambio de claves ----->|
   |=== Comunicación cifrada ======>|
```

El servidor necesita un **keystore** (almacén de claves) que contiene su certificado y clave privada. El cliente necesita un **truststore** (almacén de confianza) que contiene los certificados en los que confía.

### 2.2. Keystore y Truststore

- **Keystore (`server.keystore`):** Almacena la clave privada y el certificado del servidor. Solo lo usa el servidor. Es como su "carnet de identidad".
- **Truststore (`client.truststore`):** Almacena los certificados en los que el cliente confía. Es como la "lista de personas de confianza" del cliente.

Ambos se generan con la herramienta `keytool` incluida en el JDK.

### 2.3. FTP (File Transfer Protocol)

FTP es un protocolo de transferencia de archivos que opera sobre TCP. Utilizamos la librería **Apache Commons Net** para implementar el cliente FTP en Java.

Conceptos clave:
- **Modo pasivo (`enterLocalPassiveMode()`):** El cliente inicia ambas conexiones (control y datos), lo cual es necesario cuando hay firewalls o NAT de por medio.
- **Tipo de archivo binario (`BINARY_FILE_TYPE`):** Garantiza que el archivo se transfiera byte a byte sin modificaciones.

### 2.4. Parseo de CSV con OpenCSV

OpenCSV es una librería Java para leer y escribir archivos CSV. La clase `CSVReader` lee línea por línea y devuelve un array de `String[]` por cada fila, respetando las comillas y los delimitadores.

---

## 3. Archivos generados (SSL/TLS)

Se generaron tres archivos mediante la herramienta `keytool` del JDK:

### Comando para generar el keystore del servidor:
```bash
keytool -genkeypair -alias serverkey -keyalg RSA -keysize 2048 -validity 365 \
    -keystore server.keystore -storepass trivia123 -keypass trivia123 \
    -dname "CN=localhost, OU=Trivia, O=PSP, L=Madrid, ST=Madrid, C=ES"
```

Esto genera un par de claves RSA de 2048 bits y un certificado autofirmado válido por 365 días.

### Comando para exportar el certificado:
```bash
keytool -exportcert -alias serverkey -keystore server.keystore \
    -storepass trivia123 -file server.cer
```

### Comando para importar el certificado en el truststore del cliente:
```bash
keytool -importcert -alias serverkey -file server.cer \
    -keystore client.truststore -storepass trivia123 -noprompt
```

| Archivo | Ubicación | Quién lo usa |
|---|---|---|
| `server.keystore` | Raíz del proyecto | El Servidor |
| `client.truststore` | Raíz del proyecto | El Cliente |
| `server.cer` | Raíz del proyecto | Intermedio (se puede eliminar) |

---

## 4. Cambios en el código fuente

### 4.1. `Conectar.java` — Conexión FTP y parseo CSV

**Archivo:** `src/main/java/org/example/ServidorSocket/Conectar.java`

**Estado anterior:**
- Se conectaba a un servidor FTP ajeno (`80.225.190.216`) con credenciales `alumno/alumno`.
- Al recibir el `InputStream` del archivo, no hacía nada (había un comentario TODO).
- Tenía un bug: el campo `preguntas` era de instancia pero el método `geneararPreguntas()` era estático, lo que causaba un error de compilación.

**Estado actual:**

```java
private static final String SERVER = "35.222.249.294";
private static final int PORT = 21;
private static final String USER = "raidelmfernandez";
private static final String PASS = "911gt3rs";
private static final String REMOTE_FILE_PATH = "/home/miusuario/blooket.csv";
```

Se creó un único método público `descargarPreguntas()` que:

1. **Conecta al servidor FTP** con las credenciales configuradas.
2. **Descarga el archivo** CSV como un `InputStream`.
3. **Llama a `parsearCSV()`** para procesar el contenido.
4. **Devuelve** una `List<Pregunta>` con todas las preguntas parseadas.

El método privado `parsearCSV()` implementa la lógica de lectura del formato Blooket:

```java
private static List<Pregunta> parsearCSV(InputStream input) throws Exception {
    List<Pregunta> preguntas = new ArrayList<>();

    try (CSVReader reader = new CSVReader(new InputStreamReader(input, "UTF-8"))) {
        // Saltar fila 1 (BlooketImportTemplate) y fila 2 (headers)
        reader.readNext();
        reader.readNext();

        String[] linea;
        while ((linea = reader.readNext()) != null) {
            // ... parseo de cada fila
        }
    }
    return preguntas;
}
```

**Estructura del CSV Blooket:**

| Fila | Contenido | Acción |
|---|---|---|
| 1 | `BlooketImportTemplate,,,,,,,,` | Se ignora |
| 2 | Headers de columnas | Se ignora |
| 3+ | Datos de preguntas | Se parsea |

**Mapeo de columnas:**

| Índice CSV | Contenido | Campo de `Pregunta` |
|---|---|---|
| 0 | Question # | (se ignora) |
| 1 | Question Text | `texto` |
| 2 | Answer 1 | `opcionA` |
| 3 | Answer 2 | `opcionB` |
| 4 | Answer 3 (Opcional) | `opcionC` |
| 5 | Answer 4 (Opcional) | `opcionD` |
| 6 | Time Limit | (se ignora) |
| 7 | Correct Answer # | `respuestaCorrecta` |

**Conversión de la respuesta correcta:**

El CSV almacena el número de la respuesta correcta (1, 2, 3 o 4). Debemos convertirlo a carácter (A, B, C o D):

```java
int numRespuesta = Integer.parseInt(linea[7].trim());
respuestaCorrecta = (char) ('A' + numRespuesta - 1);
// Si numRespuesta = 1 → 'A' + 0 = 'A'
// Si numRespuesta = 2 → 'A' + 1 = 'B'
// Si numRespuesta = 3 → 'A' + 2 = 'C'
// Si numRespuesta = 4 → 'A' + 3 = 'D'
```

---

### 4.2. `Server.java` — Servidor con SSL/TLS

**Archivo:** `src/main/java/org/example/ServidorSocket/Server.java`

**Cambio 1: De `ServerSocket` a `SSLServerSocket`**

Antes:
```java
try (ServerSocket serverSocket = new ServerSocket(8080)) {
```

Después:
```java
SSLServerSocket serverSocket = crearSSLServerSocket();
```

**Cambio 2: Método `crearSSLServerSocket()`**

Este es el método central de la configuración SSL en el lado del servidor:

```java
private static SSLServerSocket crearSSLServerSocket() throws Exception {
    // 1. Cargar el keystore desde el archivo
    KeyStore keyStore = KeyStore.getInstance("JKS");
    try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
        keyStore.load(fis, KEYSTORE_PASS.toCharArray());
    }

    // 2. Inicializar el KeyManagerFactory con el keystore
    //    El KeyManager gestiona las credenciales del servidor
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm()
    );
    kmf.init(keyStore, KEYSTORE_PASS.toCharArray());

    // 3. Crear el SSLContext con el protocolo TLS
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(kmf.getKeyManagers(), null, null);

    // 4. Obtener la fábrica y crear el SSLServerSocket
    SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
    return (SSLServerSocket) factory.createServerSocket(PUERTO);
}
```

Paso a paso:
1. Se carga el fichero `server.keystore` en un objeto `KeyStore`.
2. Se inicializa un `KeyManagerFactory` que extrae la clave privada y el certificado del keystore.
3. Se crea un `SSLContext` configurado con TLS y los KeyManagers.
4. La fábrica del contexto SSL genera el `SSLServerSocket` vinculado al puerto 8080.

**Cambio 3: `inicializarPreguntas()` ahora usa FTP**

Antes estaba vacío. Ahora:
```java
private static void inicializarPreguntas() {
    System.out.println("[*] Descargando preguntas desde servidor FTP...");
    preguntas = Conectar.descargarPreguntas();
}
```

Si no se descargan preguntas, el servidor no arranca:
```java
if (preguntas.isEmpty()) {
    System.out.println("[!] No se cargaron preguntas. El servidor no puede iniciar.");
    return;
}
```

---

### 4.3. `Cliente.java` — Cliente con SSL/TLS

**Archivo:** `src/main/java/org/example/Cliente/Cliente.java`

**Cambio 1: De `Socket` a `SSLSocket`**

Antes:
```java
private Socket socket;
// ...
socket = new Socket(HOST, PUERTO);
```

Después:
```java
private SSLSocket socket;
// ...
socket = crearSSLSocket();
```

**Cambio 2: Método `crearSSLSocket()`**

```java
private SSLSocket crearSSLSocket() throws Exception {
    // 1. Cargar el truststore
    KeyStore trustStore = KeyStore.getInstance("JKS");
    try (FileInputStream fis = new FileInputStream(TRUSTSTORE_PATH)) {
        trustStore.load(fis, TRUSTSTORE_PASS.toCharArray());
    }

    // 2. Inicializar el TrustManagerFactory
    //    El TrustManager decide si el certificado del servidor es de confianza
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
    );
    tmf.init(trustStore);

    // 3. Crear SSLContext con los TrustManagers
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, tmf.getTrustManagers(), null);

    // 4. Crear el SSLSocket
    SSLSocketFactory factory = sslContext.getSocketFactory();
    return (SSLSocket) factory.createSocket(HOST, PUERTO);
}
```

La diferencia con el servidor es que aquí usamos **`TrustManagerFactory`** en lugar de `KeyManagerFactory`:
- El **servidor** necesita KeyManagers (para presentar SU certificado).
- El **cliente** necesita TrustManagers (para VERIFICAR el certificado del servidor).

---

### 4.4. `HiloCliente.java` — Sin cambios

No fue necesario modificar esta clase. `SSLSocket` extiende de `Socket`, por lo que el constructor `HiloCliente(Socket socketCliente)` acepta un `SSLSocket` sin problemas gracias al **polimorfismo** de Java.

### 4.5. `Pregunta.java` y `RespuestaCliente.java` — Sin cambios

Los modelos de datos no se ven afectados por los cambios de transporte ni de origen de datos.

---

## 5. Dependencias utilizadas

Todas estaban ya declaradas en el `pom.xml`:

| Dependencia | Versión | Uso |
|---|---|---|
| `com.opencsv:opencsv` | 5.7.1 | Parseo del archivo CSV descargado por FTP |
| `commons-net:commons-net` | 3.10.0 | Cliente FTP para conectar al servidor y descargar el CSV |
| `javax.net.ssl` (JDK) | — | SSL/TLS para cifrar la comunicación del juego |

La dependencia `jackson-databind` está declarada pero no se usa en estos cambios.

---

## 6. Diagrama de flujo del sistema

```
┌─────────────────────────────────────────────────────┐
│                   ARRANQUE DEL SERVIDOR              │
│                                                      │
│  1. Server.main() llama a inicializarPreguntas()     │
│           │                                          │
│           ▼                                          │
│  2. Conectar.descargarPreguntas()                    │
│           │                                          │
│           ▼                                          │
│  3. Conexión FTP a 35.222.249.294:21                 │
│     Usuario: raidelmfernandez                        │
│           │                                          │
│           ▼                                          │
│  4. Descarga /home/miusuario/blooket.csv             │
│           │                                          │
│           ▼                                          │
│  5. parsearCSV() con OpenCSV                         │
│     - Salta fila 1 y 2                               │
│     - Lee cada fila → crea objeto Pregunta           │
│     - Convierte nº respuesta (1-4) a letra (A-D)    │
│           │                                          │
│           ▼                                          │
│  6. Devuelve List<Pregunta> al Server                │
│           │                                          │
│           ▼                                          │
│  7. crearSSLServerSocket()                           │
│     - Carga server.keystore                          │
│     - Configura SSLContext con TLS                    │
│     - Crea SSLServerSocket en puerto 8080            │
│           │                                          │
│           ▼                                          │
│  8. Servidor escuchando conexiones SSL/TLS           │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│                   CONEXIÓN DEL CLIENTE               │
│                                                      │
│  1. Cliente.iniciar()                                │
│           │                                          │
│           ▼                                          │
│  2. crearSSLSocket()                                 │
│     - Carga client.truststore                        │
│     - Configura SSLContext con TrustManagers          │
│     - Crea SSLSocket hacia HOST:8080                 │
│           │                                          │
│           ▼                                          │
│  3. Handshake TLS                                    │
│     - Cliente verifica certificado del servidor      │
│     - Se establece canal cifrado                     │
│           │                                          │
│           ▼                                          │
│  4. Comunicación cifrada (preguntas/respuestas)      │
└─────────────────────────────────────────────────────┘
```

---

## 7. Cómo ejecutar

1. **Compilar el proyecto:**
   ```bash
   mvn compile
   ```

2. **Ejecutar el servidor** (desde la raíz del proyecto, donde están los keystores):
   ```bash
   mvn exec:java -Dexec.mainClass="org.example.ServidorSocket.Server"
   ```
   El servidor conectará al FTP, descargará las preguntas y abrirá el puerto 8080 con SSL/TLS.

3. **Ejecutar el cliente** (desde la raíz del proyecto):
   ```bash
   mvn exec:java -Dexec.mainClass="org.example.Cliente.Cliente"
   ```
   Si el cliente se ejecuta en otra máquina, hay que cambiar el `HOST` en `Cliente.java` a la IP del servidor y copiar el archivo `client.truststore`.

4. En la consola del servidor, escribir **`NEXT`** para enviar preguntas a los clientes conectados.

---

## 8. Resumen de archivos modificados

| Archivo | Cambios |
|---|---|
| `Conectar.java` | Reescrito: credenciales propias, parseo CSV con OpenCSV, método `descargarPreguntas()` |
| `Server.java` | `SSLServerSocket`, carga de keystore, `inicializarPreguntas()` con FTP |
| `Cliente.java` | `SSLSocket`, carga de truststore, método `crearSSLSocket()` |
| `server.keystore` | Nuevo: keystore con certificado autofirmado RSA 2048 bits |
| `client.truststore` | Nuevo: truststore que confía en el certificado del servidor |
