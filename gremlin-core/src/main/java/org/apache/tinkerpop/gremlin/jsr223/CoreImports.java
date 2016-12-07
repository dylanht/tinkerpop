/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.tinkerpop.gremlin.jsr223;

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.Computer;
import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.bulkdumping.BulkDumperVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.bulkloading.BulkLoaderVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.clustering.peerpressure.PeerPressureVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.strategy.decoration.VertexProgramStrategy;
import org.apache.tinkerpop.gremlin.process.computer.traversal.strategy.optimization.GraphFilterStrategy;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.RemoteGraph;
import org.apache.tinkerpop.gremlin.process.traversal.Bindings;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.SackFunctions;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Translator;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalOptionParent;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.ConnectiveStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.ElementIdStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.EventStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.HaltedTraverserStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.PartitionStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SubgraphStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.finalization.MatchAlgorithmStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.finalization.ProfileStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.AdjacentToIncidentStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.FilterRankingStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.IdentityRemovalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.IncidentToAdjacentStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.MatchPredicateStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.OrderLimitStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.PathProcessorStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RangeByIsCountStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ComputerVerificationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.LambdaRestrictionStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.StandardVerificationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMetrics;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.io.GraphReader;
import org.apache.tinkerpop.gremlin.structure.io.GraphWriter;
import org.apache.tinkerpop.gremlin.structure.io.Io;
import org.apache.tinkerpop.gremlin.structure.io.IoCore;
import org.apache.tinkerpop.gremlin.structure.io.Storage;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.apache.tinkerpop.gremlin.util.Gremlin;
import org.apache.tinkerpop.gremlin.util.TimeUtil;
import org.javatuples.Pair;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class CoreImports {

    private final static Set<Class> CLASS_IMPORTS = new HashSet<>();
    private final static Set<Method> METHOD_IMPORTS = new HashSet<>();
    private final static Set<Enum> ENUM_IMPORTS = new HashSet<>();

    static {
        /////////////
        // CLASSES //
        /////////////

        // graph
        CLASS_IMPORTS.add(Edge.class);
        CLASS_IMPORTS.add(Element.class);
        CLASS_IMPORTS.add(Graph.class);
        CLASS_IMPORTS.add(Property.class);
        CLASS_IMPORTS.add(Transaction.class);
        CLASS_IMPORTS.add(Vertex.class);
        CLASS_IMPORTS.add(VertexProperty.class);
        // tokens
        CLASS_IMPORTS.add(SackFunctions.class);
        CLASS_IMPORTS.add(SackFunctions.Barrier.class);
        CLASS_IMPORTS.add(VertexProperty.Cardinality.class);
        CLASS_IMPORTS.add(Column.class);
        CLASS_IMPORTS.add(Direction.class);
        CLASS_IMPORTS.add(Operator.class);
        CLASS_IMPORTS.add(Order.class);
        CLASS_IMPORTS.add(Pop.class);
        CLASS_IMPORTS.add(Scope.class);
        CLASS_IMPORTS.add(T.class);
        CLASS_IMPORTS.add(TraversalOptionParent.class);
        CLASS_IMPORTS.add(TraversalOptionParent.Pick.class);
        CLASS_IMPORTS.add(P.class);
        // remote
        CLASS_IMPORTS.add(RemoteConnection.class);
        CLASS_IMPORTS.add(RemoteGraph.class);
        CLASS_IMPORTS.add(EmptyGraph.class);
        // io
        CLASS_IMPORTS.add(GraphReader.class);
        CLASS_IMPORTS.add(GraphWriter.class);
        CLASS_IMPORTS.add(Io.class);
        CLASS_IMPORTS.add(IoCore.class);
        CLASS_IMPORTS.add(Storage.class);
        CLASS_IMPORTS.add(Configuration.class);
        // strategies
        CLASS_IMPORTS.add(ConnectiveStrategy.class);
        CLASS_IMPORTS.add(ElementIdStrategy.class);
        CLASS_IMPORTS.add(EventStrategy.class);
        CLASS_IMPORTS.add(HaltedTraverserStrategy.class);
        CLASS_IMPORTS.add(PartitionStrategy.class);
        CLASS_IMPORTS.add(SubgraphStrategy.class);
        CLASS_IMPORTS.add(LazyBarrierStrategy.class);
        CLASS_IMPORTS.add(MatchAlgorithmStrategy.class);
        CLASS_IMPORTS.add(ProfileStrategy.class);
        CLASS_IMPORTS.add(AdjacentToIncidentStrategy.class);
        CLASS_IMPORTS.add(FilterRankingStrategy.class);
        CLASS_IMPORTS.add(IdentityRemovalStrategy.class);
        CLASS_IMPORTS.add(IncidentToAdjacentStrategy.class);
        CLASS_IMPORTS.add(MatchPredicateStrategy.class);
        CLASS_IMPORTS.add(OrderLimitStrategy.class);
        CLASS_IMPORTS.add(PathProcessorStrategy.class);
        CLASS_IMPORTS.add(RangeByIsCountStrategy.class);
        CLASS_IMPORTS.add(ComputerVerificationStrategy.class);
        CLASS_IMPORTS.add(LambdaRestrictionStrategy.class);
        CLASS_IMPORTS.add(ReadOnlyStrategy.class);
        CLASS_IMPORTS.add(StandardVerificationStrategy.class);
        // graph traversal
        CLASS_IMPORTS.add(__.class);
        CLASS_IMPORTS.add(GraphTraversal.class);
        CLASS_IMPORTS.add(GraphTraversalSource.class);
        CLASS_IMPORTS.add(TraversalMetrics.class);
        CLASS_IMPORTS.add(Translator.class);
        CLASS_IMPORTS.add(Bindings.class);
        // graph computer
        CLASS_IMPORTS.add(Computer.class);
        CLASS_IMPORTS.add(ComputerResult.class);
        CLASS_IMPORTS.add(GraphComputer.class);
        CLASS_IMPORTS.add(Memory.class);
        CLASS_IMPORTS.add(VertexProgram.class);
        CLASS_IMPORTS.add(BulkDumperVertexProgram.class);
        CLASS_IMPORTS.add(BulkLoaderVertexProgram.class);
        CLASS_IMPORTS.add(PeerPressureVertexProgram.class);
        CLASS_IMPORTS.add(PageRankVertexProgram.class);
        CLASS_IMPORTS.add(GraphFilterStrategy.class);
        CLASS_IMPORTS.add(VertexProgramStrategy.class);
        // utils
        CLASS_IMPORTS.add(Gremlin.class);
        CLASS_IMPORTS.add(TimeUtil.class);

        /////////////
        // METHODS //
        /////////////

        uniqueMethods(IoCore.class).forEach(METHOD_IMPORTS::add);
        uniqueMethods(P.class).forEach(METHOD_IMPORTS::add);
        uniqueMethods(__.class).filter(m -> !m.getName().equals("__")).forEach(METHOD_IMPORTS::add);
        uniqueMethods(Computer.class).forEach(METHOD_IMPORTS::add);
        uniqueMethods(TimeUtil.class).forEach(METHOD_IMPORTS::add);

        ///////////
        // ENUMS //
        ///////////

        Collections.addAll(ENUM_IMPORTS, SackFunctions.Barrier.values());
        Collections.addAll(ENUM_IMPORTS, VertexProperty.Cardinality.values());
        Collections.addAll(ENUM_IMPORTS, Column.values());
        Collections.addAll(ENUM_IMPORTS, Direction.values());
        Collections.addAll(ENUM_IMPORTS, Operator.values());
        Collections.addAll(ENUM_IMPORTS, Order.values());
        Collections.addAll(ENUM_IMPORTS, Pop.values());
        Collections.addAll(ENUM_IMPORTS, Scope.values());
        Collections.addAll(ENUM_IMPORTS, T.values());
        Collections.addAll(ENUM_IMPORTS, TraversalOptionParent.Pick.values());
    }

    private CoreImports() {
        // static methods only, do not instantiate class
    }

    public static Set<Class> getClassImports() {
        return Collections.unmodifiableSet(CLASS_IMPORTS);
    }

    public static Set<Method> getMethodImports() {
        return Collections.unmodifiableSet(METHOD_IMPORTS);
    }

    public static Set<Enum> getEnumImports() {
        return Collections.unmodifiableSet(ENUM_IMPORTS);
    }

    /**
     * Filters to unique method names on each class.
     */
    private static Stream<Method> uniqueMethods(final Class<?> clazz) {
        final Set<String> unique = new HashSet<>();
        return Stream.of(clazz.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .map(m -> Pair.with(generateMethodDescriptor(m), m))
                .filter(p -> {
                    final boolean exists = unique.contains(p.getValue0());
                    if (!exists) unique.add(p.getValue0());
                    return !exists;
                })
                .map(Pair::getValue1);
    }

    private static String generateMethodDescriptor(final Method m) {
        return m.getDeclaringClass().getCanonicalName() + "." + m.getName();
    }
}
