package org.finos.legend.pure.dsl;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import org.finos.legend.pure.dsl.antlr.PureAstBuilder;
import org.finos.legend.pure.dsl.antlr.PureLexer;

/**
 * Pure language parser using ANTLR-generated lexer and parser.
 * 
 * This is the unified entry point for parsing Pure language constructs:
 * - Query expressions: Person.all()->filter()->project()
 * - Definition blocks: Class, Mapping, Database, etc.
 * 
 * Uses the merged grammar from legend-engine covering:
 * core, domain, mapping, runtime, connection, and relational grammars.
 */
public final class PureParser {

    private PureParser() {
        // Static utility class
    }

    /**
     * Parses a Pure query expression.
     * 
     * Examples:
     * - Person.all()
     * - Person.all()->filter({p | $p.age > 21})->project([{p | $p.firstName}])
     * - #>{store::DB.T_PERSON}->select(~name, ~age)
     * 
     * @param query The Pure query string
     * @return The parsed expression AST
     * @throws PureParseException if parsing fails
     */
    public static PureExpression parse(String query) {
        // Phase 2: Clean pipeline — CleanAstBuilder → AstAdapter → PureExpression
        org.finos.legend.pure.dsl.ast.ValueSpecification vs = parseClean(query);
        return org.finos.legend.pure.dsl.ast.AstAdapter.toOldAst(vs);
    }

    /**
     * Parses using the original PureAstBuilder (pre-refactoring path).
     * Kept for comparison during migration. Will be removed in Phase 3.
     */
    public static PureExpression parseLegacy(String query) {
        PureLexer lexer = new PureLexer(CharStreams.fromString(query));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        org.finos.legend.pure.dsl.antlr.PureParser parser = new org.finos.legend.pure.dsl.antlr.PureParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ErrorListener());

        org.finos.legend.pure.dsl.antlr.PureParser.ProgramLineContext tree = parser.programLine();
        PureAstBuilder visitor = new PureAstBuilder();
        visitor.setInputSource(query);
        return visitor.visit(tree);
    }

    /**
     * Parses a Pure query expression using the clean parser pipeline.
     *
     * Returns a protocol-aligned
     * {@link org.finos.legend.pure.dsl.ast.ValueSpecification}
     * AST with NO semantic dispatch — all function calls are generic
     * {@link org.finos.legend.pure.dsl.ast.AppliedFunction} nodes.
     *
     * @param query The Pure query string
     * @return The parsed ValueSpecification AST
     * @throws PureParseException if parsing fails
     */
    public static org.finos.legend.pure.dsl.ast.ValueSpecification parseClean(String query) {
        PureLexer lexer = new PureLexer(CharStreams.fromString(query));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        org.finos.legend.pure.dsl.antlr.PureParser parser = new org.finos.legend.pure.dsl.antlr.PureParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ErrorListener());

        org.finos.legend.pure.dsl.antlr.PureParser.ProgramLineContext tree = parser.programLine();
        org.finos.legend.pure.dsl.ast.CleanAstBuilder visitor = new org.finos.legend.pure.dsl.ast.CleanAstBuilder();
        visitor.setInputSource(query);
        return visitor.visit(tree);
    }

    /**
     * Parses via the clean pipeline then converts back to old AST via adapter.
     *
     * This is the Phase 2 bridge: CleanAstBuilder → AstAdapter → PureExpression.
     * Used for validation — should produce equivalent results to
     * {@link #parse(String)}.
     *
     * @param query The Pure query string
     * @return The old PureExpression AST (via adapter)
     */
    public static PureExpression parseCleanToOld(String query) {
        org.finos.legend.pure.dsl.ast.ValueSpecification vs = parseClean(query);
        return org.finos.legend.pure.dsl.ast.AstAdapter.toOldAst(vs);
    }

    /**
     * Parses a Pure definition block (Class, Mapping, Database, etc.).
     * 
     * Examples:
     * - Class model::Person { firstName: String[1]; }
     * - Database store::MyDB ( Table T_PERSON (...) )
     * - Mapping model::PersonMapping ( ... )
     * 
     * @param code The Pure definition code
     * @return The parsed definition context (for further processing)
     * @throws PureParseException if parsing fails
     */
    public static org.finos.legend.pure.dsl.antlr.PureParser.DefinitionContext parseDefinition(String code) {
        PureLexer lexer = new PureLexer(CharStreams.fromString(code));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new ErrorListener());

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        org.finos.legend.pure.dsl.antlr.PureParser parser = new org.finos.legend.pure.dsl.antlr.PureParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ErrorListener());

        return parser.definition();
    }

    /**
     * Error listener that converts ANTLR errors to PureParseException.
     */
    private static class ErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String msg,
                RecognitionException e) {
            // Use 3-argument constructor to set line/column for IDE integration
            throw new PureParseException(msg, line, charPositionInLine);
        }
    }
}
