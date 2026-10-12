package dev.danteai.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversión de audio para Simple Voice Chat, que transmite Opus a 48 kHz, mono, en frames de 960 muestras (20 ms).
 */
public final class AudioUtil {
	public static final int SVC_SAMPLE_RATE = 48_000;
	public static final int SVC_FRAME_SIZE = 960;

	private AudioUtil() {
	}

	/** Decodifica un WAV RIFF (PCM 8/16/24/32 bits o float 32) a PCM 16 bits mono. */
	public static PcmAudio decodeWav(byte[] data) {
		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (data.length < 12 || buf.getInt(0) != 0x46464952 /* RIFF */ || buf.getInt(8) != 0x45564157 /* WAVE */) {
			throw new IllegalArgumentException("No es un archivo WAV");
		}
		int pos = 12;
		int format = -1;
		int channels = 1;
		int sampleRate = 0;
		int bits = 16;
		int dataStart = -1;
		int dataLength = 0;
		while (pos + 8 <= data.length) {
			int id = buf.getInt(pos);
			long size = Integer.toUnsignedLong(buf.getInt(pos + 4));
			int body = pos + 8;
			if (id == 0x20746d66 /* "fmt " */) {
				format = Short.toUnsignedInt(buf.getShort(body));
				channels = Math.max(1, Short.toUnsignedInt(buf.getShort(body + 2)));
				sampleRate = buf.getInt(body + 4);
				bits = Short.toUnsignedInt(buf.getShort(body + 14));
				if (format == 0xFFFE && size >= 26) { // WAVE_FORMAT_EXTENSIBLE: el subformato está en el GUID
					format = Short.toUnsignedInt(buf.getShort(body + 24));
				}
			} else if (id == 0x61746164 /* "data" */) {
				dataStart = body;
				// Algunos servidores en streaming escriben tamaño 0 o 0xFFFFFFFF: se usa lo que haya.
				long available = data.length - (long) body;
				dataLength = (int) (size == 0 || size > available ? available : size);
				break;
			}
			pos = (int) Math.min(data.length, body + size + (size & 1));
		}
		if (dataStart < 0 || sampleRate <= 0) {
			throw new IllegalArgumentException("WAV sin bloque fmt/data válido");
		}
		int bytesPerSample = bits / 8;
		int frameBytes = bytesPerSample * channels;
		int frames = frameBytes == 0 ? 0 : dataLength / frameBytes;
		short[] out = new short[frames];
		for (int f = 0; f < frames; f++) {
			double sum = 0;
			for (int c = 0; c < channels; c++) {
				int off = dataStart + f * frameBytes + c * bytesPerSample;
				sum += readSample(buf, off, format, bits);
			}
			out[f] = toShort(sum / channels);
		}
		return new PcmAudio(out, sampleRate);
	}

	private static double readSample(ByteBuffer buf, int off, int format, int bits) {
		if (format == 3 && bits == 32) {
			return buf.getFloat(off) * 32767.0;
		}
		return switch (bits) {
			case 8 -> ((buf.get(off) & 0xFF) - 128) * 256.0;
			case 16 -> buf.getShort(off);
			case 24 -> ((buf.get(off) & 0xFF) | ((buf.get(off + 1) & 0xFF) << 8) | (buf.get(off + 2) << 16)) / 256.0;
			case 32 -> buf.getInt(off) / 65536.0;
			default -> throw new IllegalArgumentException("Bits por muestra no soportados: " + bits);
		};
	}

	/**
	 * Remuestrea a {@code targetRate} aplicando un factor de tono: con {@code pitch > 1} el audio se reproduce
	 * más agudo y rápido (efecto caricatura). Interpolación lineal, suficiente para voz.
	 */
	public static short[] resample(short[] in, int sourceRate, int targetRate, double pitch) {
		if (in.length == 0) return in;
		double step = (double) sourceRate * pitch / targetRate;
		int outLength = (int) Math.floor((in.length - 1) / step) + 1;
		short[] out = new short[Math.max(1, outLength)];
		for (int i = 0; i < out.length; i++) {
			double srcPos = i * step;
			int idx = (int) srcPos;
			double frac = srcPos - idx;
			int a = in[Math.min(idx, in.length - 1)];
			int b = in[Math.min(idx + 1, in.length - 1)];
			out[i] = toShort(a + (b - a) * frac);
		}
		return out;
	}

	/** Ganancia con recorte suave y fundidos de 10 ms para evitar clics al inicio y al final. */
	public static short[] applyGainAndFades(short[] in, double gain, int sampleRate) {
		short[] out = new short[in.length];
		int fade = Math.min(in.length / 2, sampleRate / 100);
		for (int i = 0; i < in.length; i++) {
			double v = in[i] * gain;
			if (i < fade) v *= (double) i / fade;
			if (i >= in.length - fade) v *= (double) (in.length - 1 - i) / Math.max(1, fade);
			out[i] = toShort(v);
		}
		return out;
	}

	/** Prepara audio para Simple Voice Chat: 48 kHz, tono, ganancia y frames de 960 muestras (el último con relleno). */
	public static List<short[]> toSvcFrames(PcmAudio audio, double pitch, double gain) {
		short[] resampled = resample(audio.samples(), audio.sampleRate(), SVC_SAMPLE_RATE, pitch);
		short[] processed = applyGainAndFades(resampled, gain, SVC_SAMPLE_RATE);
		List<short[]> frames = new ArrayList<>(processed.length / SVC_FRAME_SIZE + 1);
		for (int i = 0; i < processed.length; i += SVC_FRAME_SIZE) {
			short[] frame = new short[SVC_FRAME_SIZE];
			System.arraycopy(processed, i, frame, 0, Math.min(SVC_FRAME_SIZE, processed.length - i));
			frames.add(frame);
		}
		return frames;
	}

	/** Codifica PCM mono 16 bits como WAV (para enviar a servicios de transcripción). */
	public static byte[] encodeWav(short[] samples, int sampleRate) {
		ByteBuffer buf = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(0x46464952).putInt(36 + samples.length * 2).putInt(0x45564157);
		buf.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate)
			.putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
		buf.putInt(0x61746164).putInt(samples.length * 2);
		for (short s : samples) buf.putShort(s);
		return buf.array();
	}

	/** Nivel RMS normalizado (0..1), útil para descartar ruido. */
	public static double rms(short[] samples) {
		if (samples.length == 0) return 0;
		double sum = 0;
		for (short s : samples) sum += (double) s * s;
		return Math.sqrt(sum / samples.length) / 32768.0;
	}

	public static short[] concat(List<short[]> chunks) {
		int total = 0;
		for (short[] c : chunks) total += c.length;
		short[] out = new short[total];
		int pos = 0;
		for (short[] c : chunks) {
			System.arraycopy(c, 0, out, pos, c.length);
			pos += c.length;
		}
		return out;
	}

	static byte[] toBytes(short[] samples) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(samples.length * 2);
		for (short s : samples) {
			out.write(s & 0xFF);
			out.write((s >> 8) & 0xFF);
		}
		return out.toByteArray();
	}

	private static short toShort(double v) {
		long r = Math.round(v);
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, r));
	}
}
