package org.finos.legend.pure.dsl.ast;

/** Boolean literal. Example: {@code true} or {@code false} */
public record CBoolean(boolean value) implements ValueSpecification {
}
