package org.finos.legend.pure.dsl.ast;

/** Byte array literal. */
public record CByteArray(byte[] value) implements ValueSpecification {
}
