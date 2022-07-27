package org.elasticsearch.analysis;

import org.elasticsearch.component.ExtensibleComponent;
import org.elasticsearch.component.Nameable;

@ExtensibleComponent
public interface TokenizerFactory extends Nameable {
    Tokenizer create();
}
