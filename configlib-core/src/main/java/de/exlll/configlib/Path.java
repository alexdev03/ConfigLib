package de.exlll.configlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated element is saved
 * in a custom path
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Path {
    /**
     * Returns the custom path of the annotated configuration element.
     *
     * @return configuration element comments
     */
    String value();
}