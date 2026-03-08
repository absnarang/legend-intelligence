package org.finos.legend.pure.dsl.ast;

import org.finos.legend.pure.dsl.PureExpression;
import org.finos.legend.pure.dsl.PureParser;
import org.junit.jupiter.api.Test;

/**
 * Debug harness — compare old vs new parser output for remaining failing tests.
 * DELETE after debugging is complete.
 */
public class ParserCompare {

    private void compare(String label, String query) {
        try {
            PureExpression newResult = PureParser.parse(query);
            PureExpression oldResult = PureParser.parseLegacy(query);
            boolean equal = newResult.equals(oldResult);
            System.out.println("=== " + label + " === EQUAL=" + equal);
            if (!equal) {
                System.out.println("NEW: " + newResult);
                System.out.println("OLD: " + oldResult);
            }
        } catch (Exception e) {
            System.out.println("=== " + label + " === ERROR: " + e.getMessage());
        }
    }

    @Test
    void comparePivotCastGroupBy() {
        compare("PivotCastGroupBy",
                "|#TDS\ncity, country, year, treePlanted\nNYC, USA, 2011, 5000\nSAN, USA, 2011, 2600\nLDN, UK, 2011, 3000\nNYC, USA, 2012, 15200\n#->pivot(~[year], ~[newCol:x|$x.treePlanted:y|$y->plus()])->groupBy(~[country], ~['2011__|__newCol':x|$x.'2011__|__newCol':y|$y->plus()])->extend(~combined:x|$x.country->toOne() + '_test')");
    }

    @Test
    void comparePivotNull() {
        compare("PivotNull2",
                "|#TDS\ncity, country, year, treePlanted\nNYC, USA, 2011, 5000\nNYC, USA, 2000, 5000\nSAN, USA, 2000, 2000\nSAN, USA, 2011, 100\nLDN, UK, 2011, 3000\nSAN, USA, 2011, 2500\nNYC, USA, 2000, 10000\nNYC, USA, 2012, 7600\nNYC, USA, 2012, 7600\n#->extend(~yr:x|$x.year - 2000)->filter({x|$x.yr > 10})->select(~[city, country, year, treePlanted])->pivot(~[year], ~[newCol:x|$x.treePlanted:y|$y->sum()])->groupBy(~[country], ~['2011__|__newCol':x|$x.'2011__|__newCol':y|$y->plus(), '2012__|__newCol':x|$x.'2012__|__newCol':y|$y->plus()])->extend(~newCol:x|$x.country->toOne() + '_0')");
    }

    @Test
    void compareVariantComposition() {
        compare("VariantComposition",
                "|#TDS\nid, payload\n1, [1,2,3]\n2, [4,5,6]\n3, [7,8,9]\n4, [10,11,12]\n5, [13,14,15]\n#->filter({x|$x.payload->cast(@Variant)->toMany(@Integer)->filter({t|$t->mod(2) == 0})->isNotEmpty()})");
    }
}
