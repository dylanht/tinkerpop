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

import groovy.grape.Grape;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.DelegatingMetaClass;
import groovy.lang.MetaClass;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import groovy.lang.Tuple;
import org.apache.tinkerpop.gremlin.groovy.CompilerCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.DefaultImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.EmptyImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.ImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.NoImportCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.ConfigurationCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.InterpreterModeCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.loaders.GremlinLoader;
import org.apache.tinkerpop.gremlin.groovy.plugin.Artifact;
import org.apache.tinkerpop.gremlin.groovy.plugin.GremlinPlugin;
import org.apache.tinkerpop.gremlin.groovy.plugin.GremlinPluginException;
import org.apache.tinkerpop.gremlin.jsr223.CoreGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngineFactory;
import org.apache.tinkerpop.gremlin.jsr223.ImportCustomizer;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.jsr223.GroovyCompiledScript;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;
import org.codehaus.groovy.util.ReferenceBundle;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides methods to compile and evaluate Gremlin scripts. Compiled scripts are stored in a managed cache to cut
 * down on compilation times of future evaluations of the same script.  This {@code ScriptEngine} implementation is
 * heavily adapted from the {@code GroovyScriptEngineImpl} to include some additional functionality.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @see org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor
 */
public class GremlinGroovyScriptEngine extends GroovyScriptEngineImpl
        implements DependencyManager, AutoCloseable, GremlinScriptEngine {

    /**
     * An "internal" key for sandboxing the script engine - technically not for public use.
     */
    public static final String COMPILE_OPTIONS_VAR_TYPES = "sandbox.bindings";

    /**
     * The attribute key (passed as a binding on the context) for how to cache scripts.  The value must be one of
     * the following:
     * <ul>
     * <li>{@link #REFERENCE_TYPE_HARD}</li>
     * <li>{@link #REFERENCE_TYPE_SOFT}</li>
     * <li>{@link #REFERENCE_TYPE_WEAK}</li>
     * <li>{@link #REFERENCE_TYPE_PHANTOM}</li>
     * </ul>
     */
    public static final String KEY_REFERENCE_TYPE = "#jsr223.groovy.engine.keep.globals";

    /**
     * A value to the {@link #KEY_REFERENCE_TYPE} that immediately garbage collects the script after evaluation.
     */
    public static final String REFERENCE_TYPE_PHANTOM = "phantom";


    /**
     * A value to the {@link #KEY_REFERENCE_TYPE} that marks the script as one that can be garbage collected
     * even when memory is abundant.
     */
    public static final String REFERENCE_TYPE_WEAK = "weak";

    /**
     * A value to the {@link #KEY_REFERENCE_TYPE} that retains the script until memory is "low" and therefore
     * should be reclaimed before an {@link OutOfMemoryError} occurs.
     */
    public static final String REFERENCE_TYPE_SOFT = "soft";

    /**
     * A value to the {@link #KEY_REFERENCE_TYPE} that makes the evaluated script available in the cache for the life
     * of the JVM.
     */
    public static final String REFERENCE_TYPE_HARD = "hard";

    /**
     * Name of variable that holds local variables to be globally bound if "interpreter mode" is enabled with
     * {@link InterpreterModeCustomizerProvider}.
     */
    public static final String COLLECTED_BOUND_VARS_MAP_VARNAME = "gremlin_script_engine_collected_boundvars";

    private static final Pattern patternImportStatic = Pattern.compile("\\Aimport\\sstatic.*");

    public static final ThreadLocal<Map<String, Object>> COMPILE_OPTIONS = new ThreadLocal<Map<String, Object>>() {
        @Override
        protected Map<String, Object> initialValue() {
            return new HashMap<>();
        }
    };

    /**
     * Script to generated Class map.
     */
    private ManagedConcurrentValueMap<String, Class> classMap = new ManagedConcurrentValueMap<>(ReferenceBundle.getSoftBundle());

    /**
     * Global closures map - this is used to simulate a single global functions namespace
     */
    private ManagedConcurrentValueMap<String, Closure> globalClosures = new ManagedConcurrentValueMap<>(ReferenceBundle.getHardBundle());

    private GremlinGroovyClassLoader loader;

    private AtomicLong counter = new AtomicLong(0L);

    /**
     * The list of loaded plugins for the console.
     */
    private final Set<String> loadedPlugins = new HashSet<>();

    private volatile GremlinGroovyScriptEngineFactory factory;

    private static final String STATIC = "static";
    private static final String SCRIPT = "Script";
    private static final String DOT_GROOVY = ".groovy";
    private static final String GROOVY_LANG_SCRIPT = "groovy.lang.Script";

    private ImportCustomizerProvider importCustomizerProvider;
    private final List<CompilerCustomizerProvider> customizerProviders;

    private final ImportGroovyCustomizer importGroovyCustomizer;
    private final List<GroovyCustomizer> groovyCustomizers;

    private final Set<Artifact> artifactsToUse = new HashSet<>();
    private final boolean interpreterModeEnabled;

    /**
     * Creates a new instance using the {@link DefaultImportCustomizerProvider}.
     */
    public GremlinGroovyScriptEngine() {
        this(new Customizer[0]);
    }

    public GremlinGroovyScriptEngine(final Customizer... customizers) {
        final List<Customizer> listOfCustomizers = new ArrayList<>(Arrays.asList(customizers));

        // always need this plugin for a scriptengine to be "Gremlin-enabled"
        CoreGremlinPlugin.instance().getCustomizers("gremlin-groovy").ifPresent(c -> listOfCustomizers.addAll(Arrays.asList(c)));

        GremlinLoader.load();

        final List<ImportCustomizer> importCustomizers = listOfCustomizers.stream()
                .filter(p -> p instanceof ImportCustomizer)
                .map(p -> (ImportCustomizer) p)
                .collect(Collectors.toList());
        final ImportCustomizer[] importCustomizerArray = new ImportCustomizer[importCustomizers.size()];
        importGroovyCustomizer = new ImportGroovyCustomizer(importCustomizers.toArray(importCustomizerArray));

        groovyCustomizers = listOfCustomizers.stream()
                .filter(p -> p instanceof GroovyCustomizer)
                .map(p -> ((GroovyCustomizer) p))
                .collect(Collectors.toList());

        // determine if interpreter mode should be enabled
        interpreterModeEnabled = groovyCustomizers.stream()
                .anyMatch(p -> p.getClass().equals(InterpreterModeGroovyCustomizer.class));

        // not using the old provider model so set that to empty list so that when createClassLoader is called
        // it knows to use groovyCustomizers instead
        customizerProviders = Collections.emptyList();

        createClassLoader();
    }

    /**
     * Creates a new instance with the specified {@link CompilerCustomizerProvider} objects.
     *
     * @deprecated As of release 3.2.4, replaced by {@link #GremlinGroovyScriptEngine(Customizer...)}.
     */
    @Deprecated
    public GremlinGroovyScriptEngine(final CompilerCustomizerProvider... compilerCustomizerProviders) {
        final List<CompilerCustomizerProvider> providers = Arrays.asList(compilerCustomizerProviders);

        GremlinLoader.load();

        importCustomizerProvider = providers.stream()
                .filter(p -> p instanceof ImportCustomizerProvider)
                .map(p -> (ImportCustomizerProvider) p)
                .findFirst().orElse(NoImportCustomizerProvider.INSTANCE);

        // determine if interpreter mode should be enabled
        interpreterModeEnabled = providers.stream()
                .anyMatch(p -> p.getClass().equals(InterpreterModeCustomizerProvider.class));

        // remove used providers as the rest will be applied directly
        customizerProviders = providers.stream()
                .filter(p -> p != null && !(p instanceof ImportCustomizerProvider))
                .collect(Collectors.toList());

        // groovy customizers are not used here - set to empty list so that the customizerProviders get used
        // in createClassLoader
        groovyCustomizers = Collections.emptyList();
        importGroovyCustomizer = null;

        createClassLoader();
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method should be called after "expected" imports have been added to the {@code DependencyManager}
     * because adding imports with {@link #addImports(java.util.Set)} will reset the classloader and flush away
     * dependencies.
     */
    @Override
    public synchronized List<GremlinPlugin> use(final String group, final String artifact, final String version) {
        final Map<String, Object> dependency = new HashMap<String, Object>() {{
            put("group", group);
            put("module", artifact);
            put("version", version);
        }};

        final Map<String, Object> args = new HashMap<String, Object>() {{
            put("classLoader", loader);
        }};

        Grape.grab(args, dependency);

        // note that the service loader utilized the classloader from the groovy shell as shell class are available
        // from within there given loading through Grape.
        final List<GremlinPlugin> pluginsFound = new ArrayList<>();
        ServiceLoader.load(GremlinPlugin.class, loader).forEach(pluginsFound::add);

        artifactsToUse.add(new Artifact(group, artifact, version));

        return pluginsFound;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void loadPlugins(final List<GremlinPlugin> plugins) throws GremlinPluginException {
        for (GremlinPlugin gremlinPlugin : plugins) {
            if (!loadedPlugins.contains(gremlinPlugin.getName())) {
                gremlinPlugin.pluginTo(new ScriptEnginePluginAcceptor(this));
                loadedPlugins.add(gremlinPlugin.getName());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map[] dependencies() {
        return Grape.listDependencies(loader);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Set<String>> imports() {
        final Map<String, Set<String>> m = new HashMap<>();
        m.put("imports", importCustomizerProvider.getImports());
        m.put("staticImports", importCustomizerProvider.getStaticImports());
        m.put("extraImports", importCustomizerProvider.getExtraImports());
        m.put("extraStaticImports", importCustomizerProvider.getExtraStaticImports());
        return m;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addImports(final Set<String> importStatements) {
        // can't use this feature because imports can't come in as String for the revised model
        if (null == importCustomizerProvider)
            throw new IllegalStateException("Imports cannot be added to a GremlinGroovyScriptEngine that uses Customizer instances");

            final Set<String> staticImports = new HashSet<>();
        final Set<String> imports = new HashSet<>();

        importStatements.forEach(s -> {
            final String trimmed = s.trim();
            if (patternImportStatic.matcher(trimmed).matches()) {
                final int pos = trimmed.indexOf(STATIC);
                staticImports.add(s.substring(pos + 6).trim());
            } else
                imports.add(s.substring(6).trim());
        });

        importCustomizerProvider = new EmptyImportCustomizerProvider(importCustomizerProvider, imports, staticImports);

        internalReset();
    }

    /**
     * Get the list of loaded plugins.
     */
    public Set getPlugins() {
        return loadedPlugins;
    }

    @Override
    public Traversal.Admin eval(final Bytecode bytecode, final Bindings bindings) throws ScriptException {
        bindings.putAll(bytecode.getBindings());
        String traversalSource = "g";
        for (final Map.Entry<String, Object> entry : bindings.entrySet()) {
            if (entry.getValue() instanceof TraversalSource) {
                traversalSource = entry.getKey();
                break;
            }
        }
        return (Traversal.Admin) this.eval(GroovyTranslator.of(traversalSource).translate(bytecode), bindings);
    }

    /**
     * @deprecated As of release 3.2.4, not replaced as this class will not implement {@code AutoCloseable} in the
     * future.
     */
    @Override
    @Deprecated
    public void close() throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        internalReset();

        loadedPlugins.clear();

        getContext().getBindings(ScriptContext.ENGINE_SCOPE).clear();
    }

    /**
     * Resets the {@code ScriptEngine} but does not clear the loaded plugins or bindings.  Typically called by
     * {@link DependencyManager} methods that need to just force the classloader to be recreated and script caches
     * cleared.
     */
    private void internalReset() {
        createClassLoader();

        // must clear the local cache here because the classloader has been reset.  therefore, classes previously
        // referenced before that might not have evaluated might cleanly evaluate now.
        classMap.clear();
        globalClosures.clear();

        final Set<Artifact> toReuse = new HashSet<>(artifactsToUse);
        toReuse.forEach(this::use);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object eval(final Reader reader, final ScriptContext context) throws ScriptException {
        return eval(readFully(reader), context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object eval(final String script, final ScriptContext context) throws ScriptException {
        try {
            final String val = (String) context.getAttribute(KEY_REFERENCE_TYPE, ScriptContext.ENGINE_SCOPE);
            ReferenceBundle bundle = ReferenceBundle.getHardBundle();
            if (val != null && val.length() > 0) {
                if (val.equalsIgnoreCase(REFERENCE_TYPE_SOFT)) {
                    bundle = ReferenceBundle.getSoftBundle();
                } else if (val.equalsIgnoreCase(REFERENCE_TYPE_WEAK)) {
                    bundle = ReferenceBundle.getWeakBundle();
                } else if (val.equalsIgnoreCase(REFERENCE_TYPE_PHANTOM)) {
                    bundle = ReferenceBundle.getPhantomBundle();
                }
            }
            globalClosures.setBundle(bundle);
        } catch (ClassCastException cce) { /*ignore.*/ }

        try {
            registerBindingTypes(context);
            final Class clazz = getScriptClass(script);
            if (null == clazz) throw new ScriptException("Script class is null");
            return eval(clazz, context);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    /**
     * Create bindings to be used by this {@code ScriptEngine}.  In this case, {@link SimpleBindings} are returned.
     */
    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GremlinScriptEngineFactory getFactory() {
        if (factory == null) {
            synchronized (this) {
                if (factory == null) {
                    factory = new GremlinGroovyScriptEngineFactory();
                }
            }
        }
        return this.factory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompiledScript compile(final String scriptSource) throws ScriptException {
        try {
            return new GroovyCompiledScript(this, getScriptClass(scriptSource));
        } catch (CompilationFailedException e) {
            throw new ScriptException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompiledScript compile(final Reader reader) throws ScriptException {
        return compile(readFully(reader));
    }

    @Override
    public Object invokeFunction(final String name, final Object... args) throws ScriptException, NoSuchMethodException {
        return invokeImpl(null, name, args);
    }

    @Override
    public Object invokeMethod(final Object thiz, final String name, final Object... args) throws ScriptException, NoSuchMethodException {
        if (thiz == null) {
            throw new IllegalArgumentException("Script object can not be null");
        } else {
            return invokeImpl(thiz, name, args);
        }
    }

    @Override
    public <T> T getInterface(final Class<T> clazz) {
        return makeInterface(null, clazz);
    }

    @Override
    public <T> T getInterface(final Object thiz, final Class<T> clazz) {
        if (null == thiz) throw new IllegalArgumentException("script object is null");

        return makeInterface(thiz, clazz);
    }

    Class getScriptClass(final String script) throws CompilationFailedException {
        Class clazz = classMap.get(script);
        if (clazz != null) return clazz;

        clazz = loader.parseClass(script, generateScriptName());
        classMap.put(script, clazz);
        return clazz;
    }

    boolean isCached(final String script) {
        return classMap.get(script) != null;
    }

    Object eval(final Class scriptClass, final ScriptContext context) throws ScriptException {
        final Binding binding = new Binding(context.getBindings(ScriptContext.ENGINE_SCOPE)) {
            @Override
            public Object getVariable(String name) {
                synchronized (context) {
                    int scope = context.getAttributesScope(name);
                    if (scope != -1) {
                        return context.getAttribute(name, scope);
                    }
                    // Redirect script output to context writer, if out var is not already provided
                    if ("out".equals(name)) {
                        final Writer writer = context.getWriter();
                        if (writer != null) {
                            return (writer instanceof PrintWriter) ?
                                    (PrintWriter) writer :
                                    new PrintWriter(writer, true);
                        }
                    }
                    // Provide access to engine context, if context var is not already provided
                    if ("context".equals(name)) {
                        return context;
                    }
                }
                throw new MissingPropertyException(name, getClass());
            }

            @Override
            public void setVariable(String name, Object value) {
                synchronized (context) {
                    int scope = context.getAttributesScope(name);
                    if (scope == -1) {
                        scope = ScriptContext.ENGINE_SCOPE;
                    }
                    context.setAttribute(name, value, scope);
                }
            }
        };

        try {
            // if this class is not an instance of Script, it's a full-blown class then simply return that class
            if (!Script.class.isAssignableFrom(scriptClass)) {
                return scriptClass;
            } else {
                final Script scriptObject = InvokerHelper.createScript(scriptClass, binding);
                for (Method m : scriptClass.getMethods()) {
                    final String name = m.getName();
                    globalClosures.put(name, new MethodClosure(scriptObject, name));
                }

                final MetaClass oldMetaClass = scriptObject.getMetaClass();
                scriptObject.setMetaClass(new DelegatingMetaClass(oldMetaClass) {
                    @Override
                    public Object invokeMethod(final Object object, final String name, final Object args) {
                        if (args == null) {
                            return invokeMethod(object, name, MetaClassHelper.EMPTY_ARRAY);
                        } else if (args instanceof Tuple) {
                            return invokeMethod(object, name, ((Tuple) args).toArray());
                        } else if (args instanceof Object[]) {
                            return invokeMethod(object, name, (Object[]) args);
                        } else {
                            return invokeMethod(object, name, new Object[]{args});
                        }
                    }

                    @Override
                    public Object invokeMethod(final Object object, final String name, final Object args[]) {
                        try {
                            return super.invokeMethod(object, name, args);
                        } catch (MissingMethodException mme) {
                            return callGlobal(name, args, context);
                        }
                    }

                    @Override
                    public Object invokeStaticMethod(final Object object, final String name, final Object args[]) {
                        try {
                            return super.invokeStaticMethod(object, name, args);
                        } catch (MissingMethodException mme) {
                            return callGlobal(name, args, context);
                        }
                    }
                });

                final Object o = scriptObject.run();

                // if interpreter mode is enable then local vars of the script are promoted to engine scope bindings.
                if (interpreterModeEnabled) {
                    final Map<String, Object> localVars = (Map<String, Object>) context.getAttribute(COLLECTED_BOUND_VARS_MAP_VARNAME);
                    if (localVars != null) {
                        localVars.entrySet().forEach(e -> {
                            // closures need to be cached for later use
                            if (e.getValue() instanceof Closure)
                                globalClosures.put(e.getKey(), (Closure) e.getValue());

                            context.setAttribute(e.getKey(), e.getValue(), ScriptContext.ENGINE_SCOPE);
                        });

                        // get rid of the temporary collected vars
                        context.removeAttribute(COLLECTED_BOUND_VARS_MAP_VARNAME, ScriptContext.ENGINE_SCOPE);
                        localVars.clear();
                    }
                }

                return o;
            }
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    private void registerBindingTypes(final ScriptContext context) {
        final Map<String, ClassNode> variableTypes = new HashMap<>();
        clearVarTypes();

        // use null for the classtype if the binding value itself is null - not fully sure if that is
        // a sound way to deal with that.  didn't see a class type for null - maybe it should just be
        // unknown and be "Object".  at least null is properly being accounted for now.
        context.getBindings(ScriptContext.ENGINE_SCOPE).forEach((k, v) ->
                variableTypes.put(k, null == v ? null : ClassHelper.make(v.getClass())));

        COMPILE_OPTIONS.get().put(COMPILE_OPTIONS_VAR_TYPES, variableTypes);
    }

    private static void clearVarTypes() {
        final Map<String, Object> m = COMPILE_OPTIONS.get();
        if (m.containsKey(COMPILE_OPTIONS_VAR_TYPES))
            ((Map<String, ClassNode>) m.get(COMPILE_OPTIONS_VAR_TYPES)).clear();
    }

    private Object invokeImpl(final Object thiz, final String name, final Object args[]) throws ScriptException, NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException("Method name can not be null");
        }
        try {
            if (thiz != null) {
                return InvokerHelper.invokeMethod(thiz, name, args);
            }
        } catch (MissingMethodException mme) {
            throw new NoSuchMethodException(mme.getMessage());
        } catch (Exception e) {
            throw new ScriptException(e);
        }
        return callGlobal(name, args);
    }

    private synchronized void createClassLoader() {
        // check for customizerProviders temporarily until this deprecated stuff is gone
        if (groovyCustomizers.isEmpty() && null == importGroovyCustomizer) {
            final CompilerConfiguration conf = new CompilerConfiguration(CompilerConfiguration.DEFAULT);
            conf.addCompilationCustomizers(this.importCustomizerProvider.create());

            // ConfigurationCustomizerProvider is treated separately
            customizerProviders.stream().filter(cp -> !(cp instanceof ConfigurationCustomizerProvider))
                    .forEach(p -> conf.addCompilationCustomizers(p.create()));

            customizerProviders.stream().filter(cp -> cp instanceof ConfigurationCustomizerProvider).findFirst()
                    .ifPresent(cp -> ((ConfigurationCustomizerProvider) cp).applyCustomization(conf));

            this.loader = new GremlinGroovyClassLoader(getParentLoader(), conf);
        } else {
            final CompilerConfiguration conf = new CompilerConfiguration(CompilerConfiguration.DEFAULT);
            conf.addCompilationCustomizers(this.importGroovyCustomizer.create());

            // ConfigurationCustomizerProvider is treated separately
            groovyCustomizers.stream().filter(cp -> !(cp instanceof ConfigurationGroovyCustomizer))
                    .forEach(p -> conf.addCompilationCustomizers(p.create()));

            groovyCustomizers.stream().filter(cp -> cp instanceof ConfigurationGroovyCustomizer).findFirst()
                    .ifPresent(cp -> ((ConfigurationGroovyCustomizer) cp).applyCustomization(conf));

            this.loader = new GremlinGroovyClassLoader(getParentLoader(), conf);
        }
    }

    private void use(final Artifact artifact) {
        use(artifact.getGroup(), artifact.getArtifact(), artifact.getVersion());
    }

    private Object callGlobal(final String name, final Object args[]) {
        return callGlobal(name, args, context);
    }

    private Object callGlobal(final String name, final Object args[], final ScriptContext ctx) {
        final Closure closure = globalClosures.get(name);
        if (closure != null) {
            return closure.call(args);
        }

        final Object value = ctx.getAttribute(name);
        if (value instanceof Closure) {
            return ((Closure) value).call(args);
        } else {
            throw new MissingMethodException(name, getClass(), args);
        }
    }

    private synchronized String generateScriptName() {
        return SCRIPT + counter.incrementAndGet() + DOT_GROOVY;
    }

    @SuppressWarnings("unchecked")
    private <T> T makeInterface(final Object obj, final Class<T> clazz) {
        if (null == clazz || !clazz.isInterface()) throw new IllegalArgumentException("interface Class expected");

        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class[]{clazz},
                (proxy, m, args) -> invokeImpl(obj, m.getName(), args));
    }

    protected ClassLoader getParentLoader() {
        final ClassLoader ctxtLoader = Thread.currentThread().getContextClassLoader();
        try {
            final Class c = ctxtLoader.loadClass(GROOVY_LANG_SCRIPT);
            if (c == groovy.lang.Script.class) {
                return ctxtLoader;
            }
        } catch (ClassNotFoundException ignored) {
        }
        return groovy.lang.Script.class.getClassLoader();
    }

    private String readFully(final Reader reader) throws ScriptException {
        final char arr[] = new char[8192];
        final StringBuilder buf = new StringBuilder();
        int numChars;
        try {
            while ((numChars = reader.read(arr, 0, arr.length)) > 0) {
                buf.append(arr, 0, numChars);
            }
        } catch (IOException exp) {
            throw new ScriptException(exp);
        }
        return buf.toString();
    }
}