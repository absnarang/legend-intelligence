package org.finos.legend.pure.dsl.ast;

/** Float literal. Example: {@code 3.14} */
public record CFloat(double value) implements ValueSpecification {
}
