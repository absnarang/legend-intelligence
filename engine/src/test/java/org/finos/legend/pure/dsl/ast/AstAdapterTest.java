package org.finos.legend.pure.dsl.ast;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.finos.legend.pure.dsl.*;
import org.finos.legend.pure.dsl.antlr.PureLexer;
import org.finos.legend.pure.dsl.antlr.PureParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: Pure → CleanAstBuilder → ValueSpecification → AstAdapter →
 * PureExpression.
 * 
 * Validates that the adapter produces the same old AST types as the original
 * PureAstBuilder.
 */
class AstAdapterTest {

    /** Parse via CleanAstBuilder then convert to old AST via adapter */
    private PureExpression roundTrip(String query) {
        PureLexer lexer = new PureLexer(CharStreams.fromString(query));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PureParser parser = new PureParser(tokens);
        PureParser.ProgramLineContext tree = parser.programLine();
        CleanAstBuilder builder = new CleanAstBuilder();
        ValueSpecification newAst = builder.visit(tree);
        return AstAdapter.toOldAst(newAst);
    }

    @Nested
    class Literals {
        @Test
        void integerRoundTrip() {
            PureExpression result = roundTrip("42");
            assertInstanceOf(LiteralExpr.class, result);
            assertEquals(42L, ((LiteralExpr) result).value());
        }

        @Test
        void stringRoundTrip() {
            PureExpression result = roundTrip("'hello'");
            assertInstanceOf(LiteralExpr.class, result);
            assertEquals("hello", ((LiteralExpr) result).value());
        }

        @Test
        void booleanRoundTrip() {
            PureExpression result = roundTrip("true");
            assertInstanceOf(LiteralExpr.class, result);
            assertEquals(true, ((LiteralExpr) result).value());
        }
    }

    @Nested
    class Variables {
        @Test
        void variableRoundTrip() {
            PureExpression result = roundTrip("$x");
            assertInstanceOf(VariableExpr.class, result);
            assertEquals("x", ((VariableExpr) result).name());
        }
    }

    @Nested
    class PropertyAccess {
        @Test
        void propertyAccessRoundTrip() {
            PureExpression result = roundTrip("$x.name");
            assertInstanceOf(PropertyAccessExpression.class, result);
            var prop = (PropertyAccessExpression) result;
            assertEquals("name", prop.propertyName());
            assertInstanceOf(VariableExpr.class, prop.source());
        }
    }

    @Nested
    class ClassAllTests {
        @Test
        void personAllRoundTrip() {
            PureExpression result = roundTrip("Person.all()");
            assertInstanceOf(ClassAllExpression.class, result);
            assertEquals("Person", ((ClassAllExpression) result).className());
        }
    }

    @Nested
    class FilterTests {
        @Test
        void classFilterRoundTrip() {
            PureExpression result = roundTrip("Person.all()->filter({p|$p.age > 21})");
            // Source is ClassAllExpression (a ClassExpression), so filter should become
            // ClassFilterExpression
            assertInstanceOf(ClassFilterExpression.class, result);
            var filter = (ClassFilterExpression) result;
            assertInstanceOf(ClassAllExpression.class, filter.source());
            assertInstanceOf(LambdaExpression.class, filter.lambda());
        }
    }

    @Nested
    class LimitTests {
        @Test
        void classLimitRoundTrip() {
            PureExpression result = roundTrip("Person.all()->limit(10)");
            assertInstanceOf(ClassLimitExpression.class, result);
            assertEquals(10, ((ClassLimitExpression) result).limit());
        }
    }

    @Nested
    class ArithmeticTests {
        @Test
        void additionRoundTrip() {
            PureExpression result = roundTrip("1 + 2");
            assertInstanceOf(BinaryExpression.class, result);
            var bin = (BinaryExpression) result;
            assertEquals("+", bin.operator());
        }

        @Test
        void multiplicationRoundTrip() {
            PureExpression result = roundTrip("3 * 4");
            assertInstanceOf(BinaryExpression.class, result);
            var bin = (BinaryExpression) result;
            assertEquals("*", bin.operator());
        }
    }

    @Nested
    class ComparisonTests {
        @Test
        void equalityRoundTrip() {
            PureExpression result = roundTrip("$x == 5");
            assertInstanceOf(ComparisonExpr.class, result);
            var cmp = (ComparisonExpr) result;
            assertEquals(ComparisonExpr.Operator.EQUALS, cmp.operator());
        }

        @Test
        void greaterThanRoundTrip() {
            PureExpression result = roundTrip("$x.age > 21");
            assertInstanceOf(BinaryExpression.class, result);
            var bin = (BinaryExpression) result;
            assertEquals(">", bin.operator());
        }
    }

    @Nested
    class LambdaTests {
        @Test
        void lambdaRoundTrip() {
            PureExpression result = roundTrip("{p|$p.name}");
            assertInstanceOf(LambdaExpression.class, result);
            var lambda = (LambdaExpression) result;
            assertEquals(List.of("p"), lambda.parameters());
        }
    }

    @Nested
    class CollectionTests {
        @Test
        void arrayRoundTrip() {
            PureExpression result = roundTrip("[1, 2, 3]");
            assertInstanceOf(ArrayLiteral.class, result);
            assertEquals(3, ((ArrayLiteral) result).elements().size());
        }
    }

    @Nested
    class ColumnSpecTests {
        @Test
        void colSpecRoundTrip() {
            PureExpression result = roundTrip("~name");
            assertInstanceOf(ColumnSpec.class, result);
            var cs = (ColumnSpec) result;
            assertEquals("name", cs.name());
        }
    }

    @Nested
    class FunctionCallTests {
        @Test
        void unknownFunctionBecomesFunctionCall() {
            PureExpression result = roundTrip("Person.all()->myCustomFunc(42)");
            // myCustomFunc is not in the adapter's switch → FunctionCall
            assertInstanceOf(FunctionCall.class, result);
            var fc = (FunctionCall) result;
            assertEquals("myCustomFunc", fc.functionName());
        }
    }

    @Nested
    class RelationTests {
        @Test
        void relationLiteralRoundTrip() {
            PureExpression result = roundTrip("#>{store::DB.TABLE}#");
            assertInstanceOf(RelationLiteral.class, result);
            var rel = (RelationLiteral) result;
            assertEquals("store::DB", rel.databaseRef());
            assertEquals("TABLE", rel.tableName());
        }
    }
}
