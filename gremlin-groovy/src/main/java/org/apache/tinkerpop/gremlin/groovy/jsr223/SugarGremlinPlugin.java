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
package org.apache.tinkerpop.gremlin.groovy.jsr223;

import org.apache.tinkerpop.gremlin.groovy.loaders.SugarLoader;
import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.DefaultScriptCustomizer;

import java.util.Collections;
import java.util.HashSet;

/**
 * A plugin implementation which allows for the usage of Gremlin Groovy's syntactic sugar.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class SugarGremlinPlugin extends AbstractGremlinPlugin {

    private static final String NAME = "tinkerpop.sugar";

    public SugarGremlinPlugin() {
        super(NAME, new HashSet<>(Collections.singletonList("gremlin-groovy")), new DefaultScriptCustomizer(Collections.singletonList(
                Collections.singletonList(SugarLoader.class.getPackage().getName() + "." + SugarLoader.class.getSimpleName() + ".load()"))));
    }
}
