package org.finos.legend.pure.dsl.ast;

/** StrictDate literal (date only, no time). Example: {@code %2024-01-15} */
public record CStrictDate(String value) implements ValueSpecification {
}
