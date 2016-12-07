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

import org.apache.tinkerpop.gremlin.TestHelper;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class ScriptFileGremlinPluginTest {
    @Test
    public void shouldOpenViaPropertiesFileConfig() throws IOException {
        final File scriptFile1 = TestHelper.generateTempFileFromResource(DefaultScriptCustomizerTest.class, "script-customizer-1.groovy", ".groovy");
        final File scriptFile2 = TestHelper.generateTempFileFromResource(DefaultScriptCustomizerTest.class, "script-customizer-2.groovy", ".groovy");
        final List<String> files = new ArrayList<>();
        files.add(scriptFile1.getAbsolutePath());
        files.add(scriptFile2.getAbsolutePath());
        final GremlinPlugin plugin = ScriptFileGremlinPlugin.build().files(files).create();

        assertThat(plugin.getCustomizers().isPresent(), is(true));
        assertThat(plugin.getCustomizers().get()[0], instanceOf(ScriptCustomizer.class));
        final ScriptCustomizer customizer = (ScriptCustomizer) plugin.getCustomizers().get()[0];
        final Collection<List<String>> linesInFiles = customizer.getScripts();
        final String scriptCombined = linesInFiles.stream().flatMap(Collection::stream).map(s -> s + System.lineSeparator()).reduce("", String::concat);
        assertEquals("x = 1 + 1" +  System.lineSeparator() +
                "y = 10 * x" +   System.lineSeparator() +
                "z = 1 + x + y" +  System.lineSeparator() +
                "l = g.V(z).out()" +  System.lineSeparator() +
                "        .group().by('name')" + System.lineSeparator(), scriptCombined);

    }
}
