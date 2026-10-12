package dev.danteai.llm;

import com.google.gson.JsonObject;

/** Definición de herramienta expuesta al modelo: nombre, descripción y JSON Schema de parámetros. */
public record ToolSpec(String name, String description, JsonObject parameters) {
}
