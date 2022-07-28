package org.elasticsearch.analysis;

import org.elasticsearch.component.ExtensibleComponent;
import org.elasticsearch.component.Nameable;

import java.util.stream.Stream;

@ExtensibleComponent
public abstract class TokenFilterFactory implements AnalysisBase {

    public abstract Stream<String> create(Stream<String> tokenStream);
}
