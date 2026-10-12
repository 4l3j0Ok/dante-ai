package dev.danteai.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Modelo completo de configuración. Los valores asignados acá son los valores por defecto:
 * cualquier clave ausente en el archivo toma este valor y el archivo se reescribe con ella.
 * Los nombres de campo se serializan en snake_case.
 */
public final class DanteConfig {
	@Comment("Identidad y ciclo de vida de Dante.")
	public Dante dante = new Dante();
	@Comment("Comportamiento autónomo (funciona aunque no haya LLM).")
	public Behavior behavior = new Behavior();
	@Comment("Conexión con el modelo de lenguaje.")
	public Llm llm = new Llm();
	@Comment("Conversación por chat.")
	public Chat chat = new Chat();
	@Comment("Conversaciones espontáneas que Dante inicia por su cuenta.")
	public Spontaneous spontaneous = new Spontaneous();
	@Comment("Inventario propio de Dante.")
	public Inventory inventory = new Inventory();
	@Comment("Acceso a cofres y contenedores.")
	public Chests chests = new Chests();
	@Comment("Memoria persistente (SQLite en world/danteai/memory.db).")
	public Memory memory = new Memory();
	@Comment("Voz de Dante mediante Simple Voice Chat (opcional). Requiere que el servidor tenga instalado el mod Simple Voice Chat.")
	public Voice voice = new Voice();
	@Comment("Registro (logs).")
	public Logging logging = new Logging();

	public static final class Dante {
		@Comment("Nombre que aparece sobre su cabeza y en el chat.")
		public String name = "Dante";
		@Comment("Color del nombre en el chat (nombre de color de Minecraft, ej: gold, aqua, yellow).")
		public String name_color = "gold";
		@Comment("Crear a Dante automáticamente cerca del primer jugador si todavía no existe.")
		public boolean auto_spawn = true;
		@Comment("Dante no recibe daño (salvo /kill o el vacío).")
		public boolean invulnerable = true;
		@Comment("Segundos hasta que Dante reaparece si muere.")
		public int respawn_delay_seconds = 60;
		@Comment("Nivel de permisos de operador para los comandos /dante de administración (0-4).")
		public int admin_permission_level = 2;
		@Comment("Si es true, Dante solo aparece mientras respondan el LLM, la voz y la escucha configurados. Si dejan de responder se retira y vuelve solo cuando todos se recuperan.")
		public boolean require_services = true;
		@Comment("Segundos entre comprobaciones de que los servicios responden (3-600).")
		public int service_check_seconds = 10;
	}

	public static final class Behavior {
		@Comment("Si es false, Dante se queda quieto salvo que un jugador le pida algo.")
		public boolean autonomous = true;
		public boolean wander_enabled = true;
		public boolean follow_enabled = true;
		public boolean explore_enabled = true;
		public boolean socialize_enabled = true;
		public boolean rest_enabled = true;
		public boolean teleport_enabled = true;
		@Comment("Peso base para empezar a seguir a un jugador al elegir una nueva actividad (0-1).")
		public double follow_probability = 0.25;
		@Comment("Peso base para quedarse sentado o descansar (0-1).")
		public double idle_probability = 0.15;
		@Comment("Peso base para explorar bloques o lugares curiosos (0-1).")
		public double explore_probability = 0.20;
		@Comment("Peso base para acercarse a socializar con jugadores (0-1).")
		public double socialize_probability = 0.20;
		@Comment("Probabilidad de ignorar a un jugador en lugar de acercarse (travesura).")
		public double ignore_probability = 0.10;
		@Comment("Radio máximo en bloques de los paseos libres.")
		public int wander_radius = 12;
		@Comment("Radio en bloques para buscar cosas interesantes que explorar.")
		public int explore_radius = 10;
		@Comment("Radio en bloques en el que Dante detecta jugadores para socializar.")
		public int social_radius = 16;
		public int follow_min_seconds = 30;
		public int follow_max_seconds = 180;
		@Comment("Duración de un seguimiento pedido por un jugador (ej: 'vení conmigo').")
		public int requested_follow_seconds = 600;
		@Comment("Distancia a la que se detiene detrás del jugador seguido.")
		public double follow_stop_distance = 3.0;
		public int rest_min_seconds = 15;
		public int rest_max_seconds = 60;
		@Comment("Si el jugador seguido (o todos los jugadores) están a más de esta distancia, Dante puede teletransportarse cerca.")
		public int teleport_distance = 80;
		@Comment("Tiempo mínimo entre teletransportes.")
		public int teleport_cooldown_seconds = 90;
		@Comment("Permitir que Dante siga a jugadores a otras dimensiones.")
		public boolean teleport_across_dimensions = true;
		@Comment("Si el cuerpo de Dante quedó en chunks descargados, permitir que 'reaparezca' cerca de un jugador (sin cargar chunks a la fuerza).")
		public boolean rematerialize_when_unloaded = true;
		@Comment("Minutos sin jugadores cerca antes de que Dante vaya a buscar a alguien (teletransporte espontáneo).")
		public int lonely_minutes_before_seeking = 8;
		@Comment("Si hay más de un jugador conectado, tras estos minutos con compañía Dante avisa que va a visitar a otro jugador.")
		public boolean visit_enabled = true;
		public int visit_after_minutes = 30;
		@Comment("Segundos que espera una respuesta («quedate» o «andá») antes de irse solo.")
		public int visit_reply_seconds = 30;
		@Comment("Minutos extra que se queda si le piden que no se vaya.")
		public int visit_stay_minutes = 30;
	}

	public static final class Llm {
		@Comment("Si es false, Dante responde con un intérprete simple de órdenes y sonidos de perro.")
		public boolean enabled = true;
		@Comment("Proveedor: \"openai\" (cualquier API compatible: LM Studio, Ollama, vLLM, llama.cpp, OpenRouter...) o \"anthropic\" (Claude).")
		public String provider = "openai";
		public OpenAi openai = new OpenAi();
		public Anthropic anthropic = new Anthropic();
		@Comment("Temperatura de muestreo. Usar -1 para no enviarla.")
		public double temperature = 0.8;
		public int max_tokens = 500;
		public int timeout_seconds = 30;
		@Comment("Presupuesto aproximado de caracteres de contexto por solicitud (prompt + historial).")
		public int max_context_chars = 12000;
		@Comment("Archivo con la personalidad / system prompt (relativo a config/danteai/).")
		public String system_prompt_file = "system_prompt.md";
		@Comment("Solicitudes LLM simultáneas como máximo.")
		public int max_concurrent_requests = 2;
		@Comment("Solicitudes en espera como máximo; las demás se rechazan amablemente.")
		public int max_queue = 8;
		@Comment("Límite global de solicitudes por minuto.")
		public int requests_per_minute = 30;
		@Comment("Reintentos ante errores transitorios (429, 5xx, timeouts).")
		public int max_retries = 2;
		@Comment("Rondas máximas de herramientas por mensaje.")
		public int max_tool_rounds = 4;
	}

	public static final class OpenAi {
		public String base_url = "http://localhost:1234/v1";
		@Comment("Opcional para servidores locales.")
		public String api_key = "";
		public String model = "openai/gpt-oss-20b";
		@Comment("\"max_tokens\" (servidores locales) o \"max_completion_tokens\" (modelos nuevos de OpenAI).")
		public String max_tokens_field = "max_tokens";
		@Comment("\"native\": tool calling nativo. \"json\": protocolo JSON en texto para modelos sin soporte de herramientas.")
		public String tools_mode = "native";
		@Comment("Esfuerzo de razonamiento que se envía al modelo (\"none\", \"low\", \"medium\", \"high\"). Vacío = no enviarlo. Con modelos que piensan (Qwen3.5, gpt-oss) \"none\" o \"low\" evita que gasten los tokens razonando sin responder.")
		public String reasoning_effort = "";
		@Comment("Encabezados HTTP extra (ej: {\"HTTP-Referer\": \"...\"} para OpenRouter).")
		public java.util.Map<String, String> extra_headers = new java.util.LinkedHashMap<>();
	}

	public static final class Anthropic {
		public String base_url = "https://api.anthropic.com";
		public String api_key = "";
		@Comment("ID de modelo de Claude, ej: claude-haiku-4-5 (rápido y económico) o claude-sonnet-5-5.")
		public String model = "claude-haiku-4-5";
		public String api_version = "2023-06-01";
	}

	public static final class Chat {
		@Comment("Nombres que activan a Dante: '@Dante ...' en cualquier parte o 'Dante, ...' al inicio.")
		public List<String> trigger_names = new ArrayList<>(List.of("Dante"));
		@Comment("Si es false, Dante responde a los mensajes de los jugadores cercanos (chat.proximity_radius) sin necesidad de nombrarlo.")
		public boolean require_trigger = true;
		@Comment("Distancia en bloques para responder sin nombre cuando require_trigger es false.")
		public double proximity_radius = 12.0;
		@Comment("Segundos durante los cuales un jugador que habló con Dante puede seguir sin nombrarlo (0 = desactivado).")
		public int followup_seconds = 30;
		@Comment("Distancia máxima para que las respuestas de seguimiento sin nombre cuenten.")
		public double followup_distance = 16.0;
		@Comment("Quién ve las respuestas: \"all\" (todo el servidor) o \"nearby\" (jugadores cercanos + quien habló).")
		public String reply_audience = "all";
		public double nearby_radius = 64.0;
		@Comment("Largo máximo de cada mensaje de Dante en el chat.")
		public int max_message_length = 256;
		@Comment("Largo máximo del mensaje de un jugador que se envía al modelo.")
		public int max_input_length = 300;
		@Comment("Espera mínima entre mensajes de un mismo jugador hacia Dante.")
		public int player_cooldown_seconds = 3;
		@Comment("Solicitudes por jugador por hora como máximo.")
		public int player_requests_per_hour = 80;
	}

	public static final class Spontaneous {
		public boolean enabled = true;
		public int min_interval_seconds = 240;
		@Comment("Probabilidad de hablar cada vez que se cumple el intervalo.")
		public double chance = 0.35;
		public double radius = 16.0;
		@Comment("Usar el LLM para generar el comentario (si es false usa frases predefinidas).")
		public boolean use_llm = true;
	}

	public static final class Inventory {
		public boolean enabled = true;
		public int slots = 18;
		@Comment("Distancia para entregar objetos directamente al inventario de un jugador.")
		public double give_reach = 4.0;
		@Comment("Distancia máxima para empezar a caminar hacia un jugador para entregarle algo.")
		public double delivery_max_distance = 48.0;
		@Comment("Click derecho con un objeto en la mano se lo entrega a Dante.")
		public boolean right_click_give = true;
		@Comment("Si es true, Dante puede entregarle a cualquiera objetos que pertenecen a otro jugador si lo convencen (decide el LLM; sin LLM solo entrega los propios).")
		public boolean share_items = true;
		@Comment("Objetos que Dante puede recoger por iniciativa propia (juguetes).")
		public List<String> autonomous_pickup = new ArrayList<>(List.of("minecraft:bone", "minecraft:stick"));
	}

	public static final class Chests {
		public boolean enabled = true;
		@Comment("Radio de búsqueda de contenedores alrededor de Dante.")
		public int search_radius = 12;
		@Comment("El jugador que lo pide debe estar a esta distancia del contenedor (simula que lo podría abrir él).")
		public double max_player_distance = 24.0;
		@Comment("Solo usar contenedores cuyo nombre (puesto con yunque) contenga 'container_keyword'.")
		public boolean require_named_container = false;
		public String container_keyword = "Dante";
		public List<String> allowed_containers = new ArrayList<>(List.of(
			"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"));
	}

	public static final class Memory {
		public boolean enabled = true;
		@Comment("Si es false la memoria vive solo en RAM y se pierde al reiniciar.")
		public boolean persist = true;
		@Comment("Mensajes recientes por jugador que se envían como contexto.")
		public int context_messages = 10;
		@Comment("Mensajes guardados por jugador antes de resumir los más viejos.")
		public int summarize_after_messages = 40;
		@Comment("Hechos recordados por jugador que se incluyen en el contexto.")
		public int context_facts = 12;
		@Comment("Días que se conservan mensajes y eventos (0 = siempre).")
		public int retention_days = 60;
	}

	public static final class Voice {
		@Comment("Activa la voz. Solo tiene efecto si Simple Voice Chat está instalado en el servidor.")
		public boolean enabled = true;
		@Comment("Texto a voz: servidor compatible con la API /audio/speech de OpenAI (Kokoro-FastAPI, LocalAI, OpenAI...).")
		public OpenAiTts openai = new OpenAiTts();
		@Comment("Distancia en bloques a la que se escucha la voz de Dante.")
		public float distance = 24.0f;
		@Comment("Altura de la voz: >1 más aguda y caricaturesca, <1 más grave (también cambia la velocidad).")
		public double pitch = 1.08;
		@Comment("Ganancia de volumen (1.0 = sin cambios).")
		public double gain = 1.0;
		@Comment("Caracteres máximos que se sintetizan por mensaje.")
		public int max_characters = 600;
		@Comment("Leer en voz alta también los comentarios espontáneos.")
		public boolean speak_spontaneous = true;
		public int timeout_seconds = 30;
		@Comment("Síntesis simultáneas como máximo.")
		public int max_concurrent = 1;
		@Comment("Escuchar a los jugadores por el micrófono de Simple Voice Chat y transcribirlos (speech-to-text).")
		public Listen listen = new Listen();
	}

	public static final class OpenAiTts {
		@Comment("Servidor TTS compatible con OpenAI. Debe devolver audio WAV.")
		public String base_url = "http://127.0.0.1:28880/v1";
		public String api_key = "";
		@Comment("Modelo de voz, ej: kokoro (Kokoro-FastAPI) o gpt-4o-mini-tts.")
		public String model = "kokoro";
		@Comment("Voz del servidor TTS. En Kokoro, español: em_alex y em_santa (masculinas) o ef_dora (femenina).")
		public String voice = "em_alex";
		@Comment("Instrucciones de estilo (solo modelos que las soportan, ej: gpt-4o-mini-tts). Vacío = no enviar.")
		public String instructions = "";
		@Comment("Velocidad (0.25 - 4.0).")
		public double speed = 1.0;
	}

	public static final class Listen {
		public boolean enabled = true;
		@Comment("API compatible con OpenAI /audio/transcriptions (OpenAI, Speaches, LocalAI, whisper.cpp server...).")
		public String base_url = "http://127.0.0.1:28000/v1";
		public String api_key = "";
		public String model = "Systran/faster-whisper-small";
		@Comment("Código de idioma (vacío = autodetectar).")
		public String language = "es";
		@Comment("Distancia máxima a Dante para que escuche al jugador.")
		public double distance = 12.0;
		@Comment("Si es true, la frase tiene que mencionar el nombre de Dante (salvo que haya una conversación activa).")
		public boolean require_name = true;
		@Comment("Silencio (ms) que marca el fin de una frase.")
		public int silence_ms = 900;
		@Comment("Duración máxima de una frase en segundos.")
		public int max_seconds = 15;
		@Comment("Duración mínima de una frase en milisegundos (descarta ruidos cortos).")
		public int min_ms = 500;
	}

	public static final class Logging {
		@Comment("Mensajes de depuración en el log del servidor.")
		public boolean debug = false;
		@Comment("Registrar prompts y respuestas completas del LLM (puede contener chat de jugadores).")
		public boolean log_prompts = false;
		public boolean log_tool_calls = true;
	}
}
