package org.elasticsearch.analysis;

import java.io.IOException;
import java.io.Reader;
import java.util.stream.Stream;

// dummy class to roughly mimic lucene api
public interface Tokenizer {
    Stream<String> tokenize(Reader reader) throws IOException;
}
