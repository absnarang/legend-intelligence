package org.finos.legend.pure.dsl.ast;

/** DateTime literal. Example: {@code %2024-01-15T10:30:00} */
public record CDateTime(String value) implements ValueSpecification {
}
