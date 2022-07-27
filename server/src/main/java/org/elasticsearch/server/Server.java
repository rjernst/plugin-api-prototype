package org.elasticsearch.server;

import org.elasticsearch.analysis.TokenizerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Server {

    public static void main(String[] args) throws Exception {
        Path cratesDir = Paths.get(args[0]);

        System.out.println("Running server");
        System.out.println("  crates dir: " + cratesDir);

        var components = new ComponentService(cratesDir);

        var tokenizerFactory = components.getNamedComponent("example-tokenizer", TokenizerFactory.class);
        System.out.println("created tokenizer " + tokenizerFactory.getName() + " from " + components.getCrateNameForInstance(tokenizerFactory));
    }
}
