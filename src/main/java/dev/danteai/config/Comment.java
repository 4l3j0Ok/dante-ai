package dev.danteai.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentación de un campo de configuración. Se escribe como comentario en el archivo
 * {@code config/danteai/config.jsonc} cuando se genera o actualiza.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Comment {
	String value();
}
