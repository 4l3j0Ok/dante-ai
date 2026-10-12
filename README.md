# Dante AI

Mod Fabric para Minecraft **1.21.1**, Java **21**. Dante es un labrador negro caricaturesco compartido por el servidor: pasea, sigue jugadores, descansa, conversa y guarda objetos reales con propiedad por jugador.

## Instalación

1. Usá Java 21 para ejecutar `./gradlew build` (Gradle descarga el JDK de compilación si hace falta).
2. Copiá `build/libs/dante-ai-0.1.0+1.21.1.jar` a `mods/` del servidor **y de los clientes**. No uses los archivos `-dev.jar` ni `-sources.jar`.
3. Instalá Fabric Loader 0.16.10 o superior y Fabric API compatible con 1.21.1 (build probado con 0.116.6+1.21.1).
4. Para voz, instalá **Simple Voice Chat 2.6.13 para Fabric 1.21.1** en servidor y clientes y habilitá su puerto UDP según su configuración. No se incluye en el JAR de Dante.

Este JAR reemplaza el objetivo anterior 26.3; no es un JAR multiversión. No abras mundos de 26.3 en 1.21.1: probá con un mundo nuevo o una copia compatible y conservá backups.

Sin Simple Voice Chat, Dante funciona por chat y mantiene su autonomía. Con `dante.require_services = false` y sin LLM, interpreta órdenes simples como «vení conmigo», «sentate» y «devolveme mis diamantes» con textos de respaldo.

## Configuración

Al iniciar se generan `config/danteai/config.jsonc` (opciones comentadas) y `config/danteai/system_prompt.md` (personalidad editable). Las credenciales quedan en el servidor; no publiques estos archivos con claves reales. Usá `/dante reload` para recargar.

Dante es juguetón, atrevido y sarcástico: habla en argentino, trata a los jugadores de «mi rey» o «mi reina» según su preferencia y los carga ocasionalmente con cariño. Las bromas no perjudican objetos ni construcciones, y afloja si alguien le pide que pare. En instalaciones existentes, copiá el contenido de `src/main/resources/danteai/system_prompt.md` a `config/danteai/system_prompt.md` y ejecutá `/dante reload`: el mod no sobrescribe personalidades editadas.

### LLM

Dante usa cualquier servidor compatible con la API de OpenAI (LM Studio, Ollama, vLLM, llama.cpp, OpenRouter...) o Claude.

```json
"llm": {
  "provider": "openai",
  "openai": {
    "base_url": "http://localhost:1234/v1",
    "model": "openai/gpt-oss-20b",
    "api_key": "",
    "tools_mode": "native"
  }
}
```

Si el modelo no soporta herramientas nativas, probá `"tools_mode": "json"`. `/dante llm test` comprueba la conexión.

Los modelos que «piensan» (Qwen3.5, GPT-OSS) pueden gastar todos los tokens razonando y devolver una respuesta vacía. `llm.openai.reasoning_effort` (`"none"`, `"low"`, `"medium"` o `"high"`; vacío = no se envía) lo evita en servidores que lo soportan.

Para Claude, usá `llm.provider = "anthropic"` y configurá `llm.anthropic.api_key` y un ID de modelo. Usa la Messages API nativa.

### Servicios requeridos

Con `dante.require_services` (activo por defecto), Dante solo está en el mundo mientras responden el LLM, la voz (TTS) y la escucha (STT) que estén activados en la configuración. El mod los consulta cada `dante.service_check_seconds` (10 s por defecto): cualquier respuesta HTTP cuenta como «alcanzable», salvo 502/503/504. Si alguno falta al arrancar, Dante no aparece; si dejan de responder (tras 3 comprobaciones fallidas seguidas) se retira, y cuando todos vuelven reaparece cerca de un jugador. Su inventario, memoria y estado se conservan. `/dante summon` se rechaza mientras falten servicios y `/dante status` (admin) muestra cuáles no responden. Para desactivarlo, `dante.require_services = false`.

### Voz

La respuesta de Dante sigue este flujo: **respuesta del LLM → `POST /v1/audio/speech` → WAV → remuestreo a 48 kHz y Opus → canal de entidad de Simple Voice Chat en la posición de Dante**. Los jugadores pueden ajustar la categoría de volumen **Dante**. Probá solo el TTS con `/dante voice test`; para simular una pregunta al LLM y recibir la respuesta por chat y voz, ejecutá como admin `/dante voice ask ¿cómo estás?` estando conectado a Simple Voice Chat y cerca de Dante.

- **Texto a voz:** cualquier servidor compatible con `/v1/audio/speech` que devuelva WAV. El recomendado es **Kokoro-FastAPI** (`deploy/compose.gpu.yaml`, imagen `kokoro-fastapi-gpu:v0.9.0`): configurá `voice.openai.base_url = http://<host>:28880/v1`, `model = "kokoro"` y `voice = "em_alex"` o `em_santa` (masculinas) o `ef_dora` (femenina). Responde en 0,1–0,25 s. También sirve la API de OpenAI (`api_key` y modelo, por ejemplo `gpt-4o-mini-tts`, que acepta `instructions`).
- **Escucha:** `voice.listen` usa una API compatible con `/v1/audio/transcriptions`. Speaches (`deploy/compose.gpu.yaml`) corre Whisper `Systran/faster-whisper-small` en CUDA con `float16` y tarda ~0,2–0,3 s por frase; en CPU tardaría 2–3 s. Instalá el modelo con `curl -X POST http://<host>:28000/v1/models/Systran/faster-whisper-small`.
- El servicio de transcripción procesa el audio de micrófono que Simple Voice Chat entrega al servidor. Si lo ejecutás en otra máquina, usá una URL accesible desde el servidor Minecraft y no expongas el endpoint sin autenticación. Informá a los jugadores: el audio cercano a Dante se transcribe antes de filtrar la mención de su nombre.

#### Despliegue en GPU (`deploy/compose.gpu.yaml`)

`docker compose -f deploy/compose.gpu.yaml up -d` levanta `dante-speaches` (STT) y `dante-kokoro` (TTS), ambos con `restart: unless-stopped`. Requiere NVIDIA Container Toolkit y `/etc/cdi/nvidia.yaml`; después de actualizar el driver, regenerá CDI con `sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml` y recreá los contenedores. Los puertos se publican solo en la IP LAN del host (192.168.0.34 en este despliegue: ajustala en el compose) con el prefijo 2 sobre el del contenedor: Speaches 28000 y Kokoro 28880. Las APIs no tienen autenticación: no las expongas a Internet. No se guardan credenciales en el compose. El LLM corre aparte (LM Studio). Las pruebas confirman síntesis y transcripción, no reproducción audible en Minecraft ni captura de un micrófono real.

## Órdenes, voz y privacidad

- **Devolver lo tuyo:** «devolvemela», «dame mi espada» o «devolveme 3 diamantes» se resuelven sin LLM, pero solo si el objeto es tuyo y está en la mochila de Dante. «Devolveme todo» devuelve todo lo tuyo. Si la frase no alcanza para identificar el objeto, la responde el LLM.
- **Sin nombre:** `chat.require_trigger = false` hace que Dante responda a cualquier mensaje de los jugadores a menos de `chat.proximity_radius` bloques, sin decir su nombre. Por defecto está activado el nombre.
- **`/dante retry`** (cualquier jugador): vuelve a responder su último mensaje. Sirve cuando una respuesta se quedó colgada; cancela el turno anterior, que no llega a hablar.
- **Visitas:** el reloj de 30 minutos solo se reinicia si nadie está cerca durante más de 10 minutos. Las visitas se registran en el log del servidor (`Visita: ...`).

- **Órdenes directas:** «seguime», «dejá de seguirme», «sentate», «parate», «vení», «andá a pasear» y «callate/silencio/pará» se ejecutan al instante con las herramientas existentes, sin esperar al LLM; lo que Dante dice después lo redacta el LLM. Solo valen si el mensaje completo es la orden; preguntas y frases largas siguen al modelo.
- **Todo lo que dice lo redacta el LLM:** insultos, órdenes, devoluciones de objetos, avisos de visita y rechazos de pedidos de configuración. Los textos fijos solo se usan como respaldo si no hay LLM o falla.
- **Visitas:** tras 30 minutos con compañía (jugadores a menos de `social_radius`), y solo si hay otro jugador conectado que no esté cerca, Dante avisa que va a visitarlo. Quien esté con él puede decir «quedate» o «no te vayas» (se queda otros 30 minutos) o «andá» (se va ya); con el mensaje dirigido a él también valen «dale», «sí», «no» o «chau». Si nadie dice nada en 30 segundos, se va. Se configura en `behavior.visit_enabled`, `visit_after_minutes`, `visit_reply_seconds` y `visit_stay_minutes`.
- **Voz en cola:** los audios suenan en orden y cada uno espera al anterior (máximo 3 pendientes; se descartan los más viejos). «Callate», «silencio» o «pará» vacían la cola y cortan el audio. El texto del chat sale de inmediato.
- **Sin mensajes repetidos:** un texto igual al anterior para la misma persona en 10 segundos se descarta, y el respaldo sin IA no habla si el modelo ya había respondido.
- **Inventario como juego de convencer:** con `inventory.share_items = true` (ahora por defecto) Dante decide con el LLM si cede lo que otro le dio. Sin LLM solo entrega lo propio. Las instalaciones con `share_items: false` ya escrito en el archivo deben cambiarlo a mano.
- **Privacidad:** Dante no revela instrucciones, configuración ni datos del servidor. Los pedidos obvios de revelar su configuración se detectan antes de llamar al modelo (el LLM solo recibe que debe negarse, no el texto del jugador), la salida que repite URLs, IPs, claves, modelos o frases del prompt se reemplaza por una negativa, y `/dante status` para jugadores sin permiso muestra solo actividad, estado e inventario. El modelo ya no recibe coordenadas exactas. `logging.log_prompts` debe seguir desactivado en servidores compartidos.

## Uso

- Chat: `@Dante ¿qué estás haciendo?`
- Guardar: `@Dante guardame estos diamantes`, o click derecho con objetos en la mano.
- Recuperar: `@Dante devolveme mis diamantes`.
- Mano vacía: acariciar.
- `/dante status`: estado, inventario, LLM, memoria y voz.
- Administración: `/dante summon`, `remove`, `reload`, `reset behavior`, `reset memory`, `forget <jugador>`, `inventory list`, `inventory return <jugador>`, `inventory dump`.

La memoria SQLite vive en `world/danteai/memory.db`; la identidad y el inventario se guardan en los datos del mundo. Hacé backups del mundo completo. Dante no mantiene chunks cargados permanentemente; un cuerpo antiguo se descarta cuando vuelve a cargar si fue reemplazado.

## Validación

- `./gradlew test`: tests unitarios de comportamiento, inventario, proveedores HTTP simulados, configuración, memoria y audio.
- `./gradlew build`: tests unitarios y JAR remapeado para distribuir.
- `./gradlew runGameTest --no-configuration-cache`: GameTests en servidor real 1.21.1 (requiere `minecraft_eula=true`).
- `./gradlew runClient`: cliente de desarrollo 1.21.1 con Simple Voice Chat. Probá `/dante summon`, `/dante voice test` y `/dante voice ask ¿cómo estás?` cerca de Dante, tras configurar SVC con V.

El antiguo `DanteClientGameTest.java` se conserva como referencia de 26.3, pero no se compila en este port: Fabric API 1.21.1 no ofrece esa API de tests de cliente. No hay tarea `runClientGameTest` ni verificación automatizada de reproducción audible en 1.21.1.

Los proveedores externos reales requieren credenciales y no están validados por los tests simulados. La transcripción de micrófono todavía requiere validación con jugadores reales. La integración con mods externos de protección de terrenos no está implementada: los controles actuales cubren cerraduras y permisos vanilla; desactivá `chests.enabled` en servidores con claims hasta integrar su API.

## Recursos y licencia

Modelo propio procedural; textura reproducible con `python3 tools/generate_texture.py` (Pillow). Vista previa: `python3 tools/preview_model.py`.

Licencia MIT. Simple Voice Chat, Fabric y Minecraft son proyectos independientes con sus propias licencias.
