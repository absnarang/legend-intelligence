package org.finos.legend.pure.dsl.ast;

/**
 * Source location information for AST nodes.
 * Tracks where in the Pure source text a construct was parsed from.
 */
public record SourceInformation(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn) {

    public static final SourceInformation NONE = new SourceInformation(0, 0, 0, 0);
}
