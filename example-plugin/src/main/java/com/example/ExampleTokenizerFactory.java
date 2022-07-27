package com.example;

import org.elasticsearch.analysis.Tokenizer;
import org.elasticsearch.analysis.TokenizerFactory;
import org.elasticsearch.component.NamedComponent;

import java.io.BufferedReader;
import java.util.Arrays;

@NamedComponent(name = "example-tokenizer")
public class ExampleTokenizerFactory extends IntermediateTokenizerFactory {
    @Override
    public Tokenizer create() {
        return reader -> {
            BufferedReader br = new BufferedReader(reader);
            return Arrays.stream(br.readLine().split(" "));
        };
    }
}
