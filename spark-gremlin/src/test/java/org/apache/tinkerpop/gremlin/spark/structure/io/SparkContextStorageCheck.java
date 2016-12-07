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

package org.apache.tinkerpop.gremlin.spark.structure.io;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.hadoop.Constants;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.AbstractStorageCheck;
import org.apache.tinkerpop.gremlin.spark.structure.Spark;
import org.apache.tinkerpop.gremlin.structure.io.Storage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class SparkContextStorageCheck extends AbstractStorageCheck {

    @Before
    public void setup() throws Exception {
        super.setup();
        SparkContextStorage.open("local[4]");
        Spark.close();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        Spark.create("local[4]");
        Spark.close();
    }

    @Test
    @LoadGraphWith(LoadGraphWith.GraphData.MODERN)
    public void shouldSupportHeadMethods() throws Exception {
        final Storage storage = SparkContextStorage.open(graph.configuration());
        final String outputLocation = graph.configuration().getString(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION);
        super.checkHeadMethods(storage, graph.configuration().getString(Constants.GREMLIN_HADOOP_INPUT_LOCATION), outputLocation, PersistedInputRDD.class, PersistedInputRDD.class);
    }

    @Test
    @LoadGraphWith(LoadGraphWith.GraphData.MODERN)
    public void shouldSupportRemoveAndListMethods() throws Exception {
        final Storage storage = SparkContextStorage.open(graph.configuration());
        final String outputLocation = graph.configuration().getString(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION);
        super.checkRemoveAndListMethods(storage, outputLocation);
    }

    @Test
    @LoadGraphWith(LoadGraphWith.GraphData.MODERN)
    public void shouldSupportCopyMethods() throws Exception {
        final Storage storage = SparkContextStorage.open(graph.configuration());
        final String outputLocation = graph.configuration().getString(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION);
        final String newOutputLocation = "new-location-for-copy";
        super.checkCopyMethods(storage, outputLocation, newOutputLocation, PersistedInputRDD.class, PersistedInputRDD.class);
    }

    @Test
    @LoadGraphWith(LoadGraphWith.GraphData.MODERN)
    public void shouldNotHaveResidualDataInStorage() throws Exception {
        final Storage storage = SparkContextStorage.open(graph.configuration());
        final String outputLocation = graph.configuration().getString(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION);
        super.checkResidualDataInStorage(storage, outputLocation);
    }

    @Test
    public void shouldSupportDirectoryFileDistinction() throws Exception {
        final Storage storage = SparkContextStorage.open(graph.configuration());
        for (int i = 0; i < 10; i++) {
            JavaSparkContext.fromSparkContext(Spark.getContext()).emptyRDD().setName("directory1/file1-" + i + ".txt.bz").persist(StorageLevel.DISK_ONLY());
        }
        for (int i = 0; i < 5; i++) {
            JavaSparkContext.fromSparkContext(Spark.getContext()).emptyRDD().setName("directory2/file2-" + i + ".txt.bz").persist(StorageLevel.DISK_ONLY());
        }
        super.checkFileDirectoryDistinction(storage, "directory1", "directory2");
    }
}