package com.example;

import org.elasticsearch.analysis.TokenFilterFactory;
import org.elasticsearch.component.NamedComponent;

import java.util.Locale;
import java.util.stream.Stream;

@NamedComponent(name = "example-token-filter")
public class ExampleTokenFilterFactory extends TokenFilterFactory {

    @Override
    public Stream<String> create(Stream<String> tokenStream) {
        return tokenStream.map(s -> s.toLowerCase(Locale.ROOT));
    }
}
