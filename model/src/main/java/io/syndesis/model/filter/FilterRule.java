/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.model.filter;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.immutables.value.Value;

/**
 * Created by iocanel on 6/29/17.
 */
@Value.Immutable
@JsonDeserialize(builder = FilterRule.Builder.class)
public interface FilterRule {

    /**
     *  Path expression within the message on which to filter. Can be part of header, body, properties
     * The path must match the syntax used by the simple expression language as path
     */
    String getPath();

    /**
     * Operator to use for the filter. The value comes from meta dara obtained by the UI in
     * a separate call. Example: "contains"
     */
    String getOp();

    /**
     * Value used by operator to decide whether the filter applies
     *
     */
    String getValue();

    /**
     * Get the simple filter expression for this rule
     */
    default String getFilterExpression() {
        return "${" + getPath() + "} " + getOp() + " '" + getValue() + "'";

    }

    class Builder extends ImmutableFilterRule.Builder { }
}
