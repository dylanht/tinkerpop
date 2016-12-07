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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of the {@link ScriptCustomizer} that can create the script list from a list of files or
 * from lines of script.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class DefaultScriptCustomizer implements ScriptCustomizer {

    private final Collection<List<String>> scripts;

    public DefaultScriptCustomizer(final List<File> files) {
        this(files.stream().map(f -> {
            try {
                return Files.lines(f.toPath(), StandardCharsets.UTF_8).collect(Collectors.toList());
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe);
            }
        }).collect(Collectors.toList()));
    }

    public DefaultScriptCustomizer(final Collection<List<String>> scripts) {
        this.scripts = scripts;
    }

    public Collection<List<String>> getScripts() {
        return scripts;
    }
}
