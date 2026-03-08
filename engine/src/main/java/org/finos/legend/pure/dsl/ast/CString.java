package org.finos.legend.pure.dsl.ast;

/** String literal. Example: {@code 'hello world'} */
public record CString(String value) implements ValueSpecification {
}
