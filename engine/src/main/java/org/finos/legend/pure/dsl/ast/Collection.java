package org.finos.legend.pure.dsl.ast;

import java.util.List;

/**
 * Collection of values (list/array).
 *
 * <p>
 * Represents {@code [a, b, c]} in Pure. Used for:
 * <ul>
 * <li>Lambda arrays: {@code [{p|$p.name}, {p|$p.age}]}</li>
 * <li>Alias arrays: {@code ['name', 'age']}</li>
 * <li>General collections: {@code [1, 2, 3]}</li>
 * </ul>
 *
 * @param values The elements of the collection
 */
public record Collection(
        List<ValueSpecification> values) implements ValueSpecification {

    public Collection {
        values = List.copyOf(values);
    }
}
