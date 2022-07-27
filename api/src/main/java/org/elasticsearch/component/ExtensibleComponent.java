package org.elasticsearch.component;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Marker for things that can be loaded by component loader.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value={TYPE})
public @interface ExtensibleComponent {
}
