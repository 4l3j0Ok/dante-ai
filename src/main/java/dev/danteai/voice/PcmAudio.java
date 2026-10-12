package dev.danteai.voice;

/** Audio PCM mono de 16 bits. */
public record PcmAudio(short[] samples, int sampleRate) {
	public double durationSeconds() {
		return sampleRate <= 0 ? 0 : (double) samples.length / sampleRate;
	}
}
