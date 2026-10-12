package dev.danteai.llm;

/** Error de proveedor. {@link #retryable()} indica si conviene reintentar (429, 5xx, red, timeout). */
public class LlmException extends RuntimeException {
	private final int status;
	private final boolean retryable;
	private final long retryAfterMillis;

	public LlmException(String message, int status, boolean retryable, long retryAfterMillis, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.retryable = retryable;
		this.retryAfterMillis = retryAfterMillis;
	}

	public LlmException(String message, int status, boolean retryable) {
		this(message, status, retryable, -1, null);
	}

	public int status() {
		return status;
	}

	public boolean retryable() {
		return retryable;
	}

	public long retryAfterMillis() {
		return retryAfterMillis;
	}

	public static boolean isRetryableStatus(int status) {
		return status == 408 || status == 409 || status == 425 || status == 429 || status == 529 || (status >= 500 && status <= 599);
	}
}
