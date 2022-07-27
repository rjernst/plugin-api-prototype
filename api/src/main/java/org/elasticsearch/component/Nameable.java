package org.elasticsearch.component;

public interface Nameable {
    default String getName() {
        NamedComponent nameable = getClass().getAnnotation(NamedComponent.class);
        return nameable.name();
    }
}
