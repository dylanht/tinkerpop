/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.jsr223;

import java.util.Optional;

/**
 * This module is required for a {@code ScriptEngine} to be Gremlin-enabled.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @deprecated As of release 3.2.4, replaced by {@link CoreGremlinPlugin}.
 */
@Deprecated
public final class CoreGremlinModule implements GremlinModule {

    private static final String MODULE_NAME = "tinkerpop.core";

    private static final ImportCustomizer gremlinCore = DefaultImportCustomizer.build()
            .addClassImports(CoreImports.getClassImports())
            .addEnumImports(CoreImports.getEnumImports())
            .addMethodImports(CoreImports.getMethodImports()).create();

    private static final Customizer[] customizers = new Customizer[] {gremlinCore};

    /**
     * @deprecated As of 3.2.4, replaced by {@link #instance()} as this field will later become private.
     */
    @Deprecated
    public static final CoreGremlinModule INSTANCE = new CoreGremlinModule();

    private CoreGremlinModule() {}

    public static CoreGremlinModule instance() {
        return INSTANCE;
    }

    @Override
    public Optional<Customizer[]> getCustomizers(final String scriptEngineName) {
        return Optional.of(customizers);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }
}
