/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.datastores.aggregation.query;

import com.yahoo.elide.datastores.aggregation.metadata.enums.ColumnType;
import com.yahoo.elide.datastores.aggregation.metadata.enums.ValueType;
import com.yahoo.elide.request.Argument;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * Represents a projected column as an alias in a query.
 */
public interface ColumnProjection extends Serializable {
    /**
     * Get the query source associated with the column.
     * @return the query source
     */
    Queryable getSource();

    /**
     * Get the projection alias.
     *
     * @return alias
     */
    default String getAlias() {
        return getName();
    }

    /**
     * Returns a unique identifier for the column.
     * @return a unique column ID
     */
    default String getId() {
        return getSource().getName() + "." + getName();
    }

    /**
     * Returns the name of the column.
     * @return the name of the column.
     */
    String getName();

    /**
     * Returns the query engine specific definition of the column.
     * @return the definition of the column.
     */
    String getExpression();

    /**
     * Returns the value type of the column.
     * @return the value type of the column.
     */
    ValueType getValueType();

    /**
     * Returns the column type of the column.
     * @return the column type of the column.
     */
    ColumnType getColumnType();

    /**
     * Get all arguments provided for this metric function.
     *
     * @return request arguments
     */
    default Map<String, Argument> getArguments() {
        return Collections.EMPTY_MAP;
    }

    // force implementations to define equals/hashCode
    boolean equals(Object other);
    int hashCode();

    /**
     * Makes a copy of this column with a new source.
     * @param source The new source.
     * @return copy of the column projection.
     */
    default ColumnProjection withSource(Queryable source) {
        throw new UnsupportedOperationException();
    }

    /**
     * Makes a copy of this column with a new source and expression.
     * @param source The new source.
     * @param expression The new expression.
     * @return copy of the column projection.
     */
    default ColumnProjection withSourceAndExpression(Queryable source, String expression) {
        throw new UnsupportedOperationException();
    }
}
