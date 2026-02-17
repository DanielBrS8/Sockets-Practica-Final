# Servidor Trivia Multijugador con TLS

## Modulo: Programacion de Servicios y Procesos (PSP)
### Ciclo Formativo: Desarrollo de Aplicaciones Multiplataforma (DAM)

---

## 1. Descripcion del proyecto

Juego de trivia multijugador basado en una arquitectura **cliente-servidor**. La comunicacion se realiza mediante **sockets TCP con protocolo HTTP construido manualmente** y cifrado **TLS** (Transport Layer Security).

- El servidor expone endpoints HTTP sobre sockets raw (sin usar librerias como Jetty o Spring).
- Los clientes se conectan, se registran con un nombre, consultan preguntas y envian respuestas.
- El servidor controla el flujo del juego desde la consola con comandos (`NEXT`, `RESET`).

---

## 2. Estructura del proyecto

```
WebSockets_PSP/
|-- pom.xml                          # Configuracion Maven (JDK 24)
|-- mvnw / mvnw.cmd                  # Maven Wrapper
|-- src/
|   |-- main/
|       |-- java/org/example/
|       |   |-- ServidorHTTP/
|       |   |   |-- ServidorHTTP.java     # Servidor principal (TLS + HTTP)
|       |   |   |-- Pregunta.java         # Modelo de datos de una pregunta
|       |   |   |-- RespuestaDTO.java     # DTO de respuesta del jugador
|       |   |   |-- RegistroHandler.java  # Handler POST /registro
|       |   |   |-- PreguntaHandler.java  # Handler GET /pregunta
|       |   |   |-- RespuestaHandler.java # Handler POST /respuesta
|       |   |-- Cliente/
|       |       |-- ClienteHTTP.java      # Cliente interactivo (TLS + HTTP)
|       |-- resources/
|           |-- keystore.p12              # Certificado + clave privada (servidor)
|           |-- truststore.p12            # Certificado de confianza (cliente)
|           |-- trivia.cer                # Certificado exportado (intermedio)
```

---

## 3. Protocolo de comunicacion

La comunicacion usa **HTTP/1.1 sobre sockets TCP con TLS**. Tanto el cliente como el servidor construyen y parsean las peticiones/respuestas HTTP manualmente (sin librerias HTTP).

### 3.1. Endpoints del servidor

| Metodo | Ruta | Descripcion |
|--------|------|-------------|
| POST | `/registro` | Registra un jugador con un nombre |
| GET | `/pregunta` | Obtiene la pregunta activa |
| POST | `/respuesta` | Envia la respuesta del jugador |

### 3.2. Formato de peticiones y respuestas

**POST /registro**

Request body:
```json
{"nombre": "Juan"}
```

Response:
```json
{"ok": true, "id": "a1b2c3d4", "nombre": "Juan"}
```

**GET /pregunta**

Sin pregunta activa:
```json
{"hay_pregunta": false}
```

Con pregunta activa:
```json
{
  "hay_pregunta": true,
  "numero": 1,
  "total": 3,
  "texto": "Capital de Francia?",
  "opciones": ["A) Londres", "B) Paris", "C) Madrid", "D) Berlin"]
}
```

**POST /respuesta**

Request body:
```json
{"id": "a1b2c3d4", "respuesta": "B"}
```

Response:
```json
{"ok": true, "tiempo": 3200, "correcta": true}
```

---

## 4. Descripcion de las clases

### 4.1. ServidorHTTP.java

Clase principal del servidor. Responsabilidades:

- **Configura TLS**: Carga el `keystore.p12` con el certificado autofirmado y crea un `SSLServerSocket`.
- **Acepta conexiones**: Un hilo daemon acepta conexiones SSL entrantes y las delega a un pool de 10 hilos (`ExecutorService`).
- **Enruta peticiones**: El metodo `manejarConexion()` lee la peticion HTTP raw (metodo, ruta, cabeceras, body) y la enruta al handler correspondiente segun la ruta.
- **Gestiona el juego**: Mantiene el estado del juego (jugadores registrados, pregunta actual, respuestas recibidas).
- **Comandos de consola**: El hilo principal lee comandos del operador:
  - `NEXT` - Avanza a la siguiente pregunta.
  - `RESET` - Reinicia el juego.

Metodos publicos para los handlers:
- `registrarJugador(nombre)` - Genera un ID unico y almacena al jugador.
- `getPreguntaActual()` - Devuelve la pregunta activa o null.
- `procesarRespuesta(id, respuesta)` - Valida la respuesta, calcula el tiempo y devuelve un `RespuestaDTO`.

### 4.2. ClienteHTTP.java

Cliente interactivo por consola. Responsabilidades:

- **Configura TLS**: Carga el `truststore.p12` para verificar el certificado del servidor y crea una `SSLSocketFactory`.
- **Registro**: Pide al usuario la IP del servidor y su nombre, y hace un POST /registro.
- **Bucle de comandos**: El usuario interactua con comandos:
  - `POLL` - Consulta la pregunta activa (GET /pregunta).
  - `A`, `B`, `C`, `D` - Envia una respuesta (POST /respuesta).
  - `SALIR` - Cierra el cliente.
- **HTTP manual**: Los metodos `get()` y `post()` construyen peticiones HTTP/1.1 a mano y las envian por el SSLSocket. El metodo `leerRespuestaHTTP()` parsea la respuesta (status line, cabeceras, body).

### 4.3. Pregunta.java

Modelo de datos que representa una pregunta de trivia.

Campos:
- `texto` - Enunciado de la pregunta.
- `opcionA`, `opcionB`, `opcionC`, `opcionD` - Las cuatro opciones.
- `respuestaCorrecta` - Caracter (`A`, `B`, `C` o `D`) de la respuesta correcta.

### 4.4. RespuestaDTO.java

DTO (Data Transfer Object) que encapsula el resultado de una respuesta.

Campos:
- `nombre` - Nombre del jugador.
- `respuesta` - Letra respondida.
- `tiempoMs` - Milisegundos desde que se lanzo la pregunta.
- `correcta` - Si la respuesta fue correcta.
- `error` - Mensaje de error (si aplica).

Metodo `toJson()`: Serializa el DTO a JSON manualmente.

### 4.5. RegistroHandler.java

Procesa peticiones POST /registro. Extrae el campo `nombre` del body JSON y llama a `ServidorHTTP.registrarJugador()`. Devuelve el ID generado.

### 4.6. PreguntaHandler.java

Procesa peticiones GET /pregunta. Consulta `ServidorHTTP.getPreguntaActual()`. Si no hay pregunta activa devuelve `{"hay_pregunta": false}`, si la hay devuelve el texto y las opciones en JSON.

### 4.7. RespuestaHandler.java

Procesa peticiones POST /respuesta. Extrae los campos `id` y `respuesta` del body JSON, valida que la respuesta sea A-D y llama a `ServidorHTTP.procesarRespuesta()`.

---

## 5. Seguridad: TLS (Transport Layer Security)

### 5.1. Que es TLS

TLS es el protocolo estandar para cifrar comunicaciones en red. Es el sucesor de SSL (obsoleto). Proporciona:

- **Confidencialidad**: Los datos viajan cifrados, no se pueden leer si se interceptan.
- **Integridad**: Se detecta cualquier modificacion de los datos en transito.
- **Autenticacion**: El cliente puede verificar la identidad del servidor mediante su certificado.

### 5.2. Handshake TLS

```
Cliente                             Servidor
   |--- ClientHello ------------------>|
   |<-- ServerHello + Certificado -----|
   |--- Verificacion del certificado ->|
   |<-- Intercambio de claves -------->|
   |=== Comunicacion cifrada =========>|
```

### 5.3. Keystore y Truststore

| Archivo | Formato | Quien lo usa | Contenido |
|---------|---------|-------------|-----------|
| `keystore.p12` | PKCS12 | Servidor | Clave privada + certificado autofirmado (RSA 2048 bits) |
| `truststore.p12` | PKCS12 | Cliente | Certificado publico del servidor (para verificar su identidad) |

### 5.4. Clases Java utilizadas

| Clase | Paquete | Funcion |
|-------|---------|---------|
| `SSLContext` | `javax.net.ssl` | Configura el protocolo TLS |
| `SSLServerSocket` | `javax.net.ssl` | Socket servidor que acepta conexiones cifradas |
| `SSLSocket` | `javax.net.ssl` | Socket cliente que establece conexiones cifradas |
| `SSLServerSocketFactory` | `javax.net.ssl` | Fabrica de SSLServerSocket |
| `SSLSocketFactory` | `javax.net.ssl` | Fabrica de SSLSocket |
| `KeyManagerFactory` | `javax.net.ssl` | Gestiona la clave privada del servidor |
| `TrustManagerFactory` | `javax.net.ssl` | Gestiona los certificados de confianza del cliente |
| `KeyStore` | `java.security` | Carga keystores/truststores desde archivos .p12 |

### 5.5. Implementacion en el servidor

```java
// 1. Cargar keystore
KeyStore ks = KeyStore.getInstance("PKCS12");
ks.load(ServidorHTTP.class.getResourceAsStream("/keystore.p12"), "trivia123".toCharArray());

// 2. Crear KeyManager con la clave privada
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(ks, "trivia123".toCharArray());

// 3. Configurar SSLContext
SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(kmf.getKeyManagers(), null, null);

// 4. Crear SSLServerSocket
SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
ServerSocket serverSocket = factory.createServerSocket(7070);
```

### 5.6. Implementacion en el cliente

```java
// 1. Cargar truststore
KeyStore ts = KeyStore.getInstance("PKCS12");
ts.load(ClienteHTTP.class.getResourceAsStream("/truststore.p12"), "trivia123".toCharArray());

// 2. Crear TrustManager que verifica el certificado del servidor
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(ts);

// 3. Configurar SSLContext
SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(null, tmf.getTrustManagers(), null);

// 4. Crear SSLSocket
SSLSocketFactory factory = sslContext.getSocketFactory();
Socket socket = factory.createSocket("localhost", 7070);
```

### 5.7. Comandos keytool para regenerar los certificados

```bash
# Generar keystore con certificado autofirmado (servidor)
keytool -genkeypair -alias trivia -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore keystore.p12 -storepass trivia123 -validity 365 \
    -dname "CN=localhost, OU=Dev, O=Trivia, L=Madrid, ST=Madrid, C=ES"

# Exportar certificado
keytool -exportcert -alias trivia -keystore keystore.p12 \
    -storepass trivia123 -file trivia.cer

# Importar certificado en truststore (cliente)
keytool -importcert -alias trivia -file trivia.cer \
    -keystore truststore.p12 -storetype PKCS12 -storepass trivia123 -noprompt
```

---

## 6. Dependencias (pom.xml)

| Dependencia | Version | Uso |
|-------------|---------|-----|
| `com.opencsv:opencsv` | 5.7.1 | Parseo de archivos CSV |
| `com.jcraft:jsch` | 0.1.55 | Cliente SSH/SFTP |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.0 | Serializacion JSON |
| `javax.net.ssl` (JDK) | -- | TLS para cifrar la comunicacion |

Plugin `maven-shade-plugin` 3.6.0: Genera un JAR ejecutable con todas las dependencias incluidas. La clase principal es `org.example.ServidorHTTP.ServidorHTTP`.

---

## 7. Como ejecutar

### Requisitos
- JDK 24 instalado
- Variable `JAVA_HOME` configurada:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
  ```

### Servidor (Terminal 1)
```bash
./mvnw compile exec:java "-Dexec.mainClass=org.example.ServidorHTTP.ServidorHTTP"
```

El servidor arrancara en el puerto 7070 con TLS. Comandos disponibles:
- `NEXT` - Lanza la siguiente pregunta.
- `RESET` - Reinicia el juego.

### Cliente (Terminal 2)
```bash
./mvnw compile exec:java "-Dexec.mainClass=org.example.Cliente.ClienteHTTP"
```

El cliente pedira la IP del servidor (pulsar Enter para `localhost`) y un nombre. Comandos:
- `POLL` - Consulta la pregunta activa.
- `A`, `B`, `C`, `D` - Envia una respuesta.
- `SALIR` - Cierra el cliente.

### Generar JAR ejecutable
```bash
./mvnw package
java -jar target/ServerSockerMi-1.0-SNAPSHOT.jar
```

---

## 8. Flujo del juego

```
  SERVIDOR                                    CLIENTE
  ========                                    =======

  Arranca en puerto 7070 (TLS)
  Esperando conexiones...
                                              Conecta via SSLSocket
                                              POST /registro {"nombre":"Juan"}
  Registra jugador, genera ID
  Responde {"ok":true, "id":"a1b2c3d4"}
                                              Almacena su ID

  Operador escribe NEXT
  Pregunta 1 activa
                                              POLL -> GET /pregunta
  Responde con la pregunta y opciones
                                              Muestra la pregunta al usuario
                                              Usuario escribe B
                                              POST /respuesta {"id":"...","respuesta":"B"}
  Valida respuesta, calcula tiempo
  Responde {"ok":true, "tiempo":3200, "correcta":true}
                                              Muestra resultado

  Operador escribe NEXT
  Pregunta 2 activa...
```

---

## 9. Concurrencia

- El servidor usa un `ExecutorService` con pool de 10 hilos para manejar conexiones simultaneas.
- Los mapas de jugadores y respuestas usan `ConcurrentHashMap` para acceso seguro desde multiples hilos.
- Cada peticion HTTP abre una nueva conexion TLS (Connection: close), por lo que no hay estado compartido entre peticiones a nivel de socket.
