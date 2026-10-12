package dev.danteai.tools;

/** Error de validación o de ejecución que se informa al modelo como resultado fallido (no es un bug). */
public final class ToolException extends Exception {
	public ToolException(String message) {
		super(message, null, false, false);
	}
}
