package io.quarkus.deployment.configuration;

import static io.quarkus.deployment.util.ReflectUtil.rawTypeOf;
import static io.quarkus.deployment.util.ReflectUtil.rawTypeOfParameter;
import static io.quarkus.deployment.util.ReflectUtil.typeOfParameter;
import static io.quarkus.deployment.util.StringUtil.camelHumpsIterator;
import static io.quarkus.deployment.util.StringUtil.hyphenate;
import static io.quarkus.deployment.util.StringUtil.join;
import static io.quarkus.deployment.util.StringUtil.lowerCase;
import static io.quarkus.deployment.util.StringUtil.lowerCaseFirst;
import static io.quarkus.deployment.util.StringUtil.withoutSuffix;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

import org.jboss.logging.Logger;
import org.objectweb.asm.Opcodes;
import org.wildfly.common.Assert;

import io.quarkus.deployment.AccessorFinder;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.DescriptorUtils;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.ExpandingConfigSource;
import io.quarkus.runtime.configuration.NameIterator;
import io.smallrye.config.SmallRyeConfig;

/**
 * A configuration definition. This class represents the configuration space as trees of nodes, where each tree
 * has a root which recursively contains all of the elements within the configuration.
 */
public class ConfigDefinition extends CompoundConfigType {
    private static final Logger log = Logger.getLogger("io.quarkus.config");

    public static final String NO_CONTAINING_NAME = "<<ignored>>";

    private static final String QUARKUS_NAMESPACE = "quarkus";

    // for now just list the values manually
    private static final List<String> FALSE_POSITIVE_QUARKUS_CONFIG_MISSES = Arrays
            .asList(QUARKUS_NAMESPACE + ".live-reload.password", QUARKUS_NAMESPACE + ".live-reload.url",
                    QUARKUS_NAMESPACE + ".debug.generated-classes-dir", QUARKUS_NAMESPACE + ".debug.reflection");

    private final TreeMap<String, Object> rootObjectsByContainingName = new TreeMap<>();
    private final HashMap<Class<?>, Object> rootObjectsByClass = new HashMap<>();
    private final ConfigPatternMap<LeafConfigType> leafPatterns = new ConfigPatternMap<>();
    private final IdentityHashMap<Object, ValueInfo> realizedInstances = new IdentityHashMap<>();
    private final TreeMap<String, RootInfo> rootTypesByContainingName = new TreeMap<>();
    private final FieldDescriptor rootField;
    private final TreeMap<String, String> loadedProperties = new TreeMap<>();

    public ConfigDefinition(final FieldDescriptor rootField) {
        super(null, null, false);
        Assert.checkNotNullParam("rootField", rootField);
        this.rootField = rootField;
    }

    void acceptConfigurationValueIntoLeaf(final LeafConfigType leafType, final NameIterator name,
            final ExpandingConfigSource.Cache cache, final SmallRyeConfig config) {
        // primitive/leaf values without a config group
        throw Assert.unsupported();
    }

    void generateAcceptConfigurationValueIntoLeaf(final BytecodeCreator body, final LeafConfigType leafType,
            final ResultHandle name, final ResultHandle cache, final ResultHandle config) {
        // primitive/leaf values without a config group
        throw Assert.unsupported();
    }

    Object getChildObject(final NameIterator name, final ExpandingConfigSource.Cache cache, final SmallRyeConfig config,
            final Object self, final String childName) {
        return rootObjectsByContainingName.get(childName);
    }

    ResultHandle generateGetChildObject(final BytecodeCreator body, final ResultHandle name, final ResultHandle cache,
            final ResultHandle config,
            final ResultHandle self, final String childName) {
        return body.readInstanceField(rootTypesByContainingName.get(childName).getFieldDescriptor(), self);
    }

    TreeMap<String, Object> getOrCreate(final NameIterator name, final ExpandingConfigSource.Cache cache,
            final SmallRyeConfig config) {
        return rootObjectsByContainingName;
    }

    ResultHandle generateGetOrCreate(final BytecodeCreator body, final ResultHandle name, final ResultHandle cache,
            final ResultHandle config) {
        return body.readStaticField(rootField);
    }

    void setChildObject(final NameIterator name, final Object self, final String childName, final Object value) {
        if (self != rootObjectsByContainingName)
            throw new IllegalStateException("Wrong self pointer: " + self);
        final RootInfo rootInfo = rootTypesByContainingName.get(childName);
        assert rootInfo != null : "Unknown child: " + childName;
        assert !rootObjectsByContainingName.containsKey(childName) : "Child added twice: " + childName;
        rootObjectsByContainingName.put(childName, value);
        rootObjectsByClass.put(rootInfo.getRootClass(), value);
        realizedInstances.put(value, new ValueInfo(childName, rootInfo));
    }

    void generateSetChildObject(final BytecodeCreator body, final ResultHandle name, final ResultHandle self,
            final String containingName, final ResultHandle value) {
        // objects should always be pre-initialized
        throw Assert.unsupported();
    }

    void getDefaultValueIntoEnclosingGroup(final Object enclosing, final ExpandingConfigSource.Cache cache,
            final SmallRyeConfig config, final Field field) {
        throw Assert.unsupported();
    }

    void generateGetDefaultValueIntoEnclosingGroup(final BytecodeCreator body, final ResultHandle enclosing,
            final MethodDescriptor setter, final ResultHandle cache, final ResultHandle config) {
        throw Assert.unsupported();
    }

    public ResultHandle writeInitialization(final BytecodeCreator body, final AccessorFinder accessorFinder,
            final ResultHandle cache, final ResultHandle smallRyeConfig) {
        throw Assert.unsupported();
    }

    public void load() {
        loadFrom(leafPatterns);
    }

    public void initialize(final SmallRyeConfig config, final ExpandingConfigSource.Cache cache) {
        for (Map.Entry<String, RootInfo> entry : rootTypesByContainingName.entrySet()) {
            final RootInfo rootInfo = entry.getValue();
            // name iterator and config are always ignored because no root types are ever stored in a map node and no conversion is ever done
            // TODO: make a separate create method for root types just to avoid this kind of thing
            rootInfo.getRootType().getOrCreate(new NameIterator("ignored", true), cache, config);
        }
    }

    public void registerConfigRoot(Class<?> configRoot) {
        final AccessorFinder accessorFinder = new AccessorFinder();
        final ConfigRoot configRootAnnotation = configRoot.getAnnotation(ConfigRoot.class);
        if (configRoot.isAnnotationPresent(ConfigGroup.class)) {
            throw reportError(configRoot, "Roots cannot have a @ConfigGroup annotation");
        }
        final String containingName = join(
                withoutSuffix(lowerCaseFirst(camelHumpsIterator(configRoot.getSimpleName())), "Config", "Configuration"));
        final String name = configRootAnnotation.name();
        final String rootName;
        if (name.equals(ConfigItem.PARENT)) {
            throw reportError(configRoot, "Root cannot inherit parent name because it has no parent");
        } else if (name.equals(ConfigItem.ELEMENT_NAME)) {
            rootName = containingName;
        } else if (name.equals(ConfigItem.HYPHENATED_ELEMENT_NAME)) {
            rootName = join("-",
                    withoutSuffix(lowerCase(camelHumpsIterator(configRoot.getSimpleName())), "config", "configuration"));
        } else {
            rootName = name;
        }
        final ConfigPhase configPhase = configRootAnnotation.phase();
        if (rootTypesByContainingName.containsKey(containingName))
            throw reportError(configRoot, "Duplicate configuration root name \"" + containingName + "\"");
        final GroupConfigType configGroup = processConfigGroup(containingName, this, true, rootName, configRoot,
                accessorFinder);
        final RootInfo rootInfo = new RootInfo(configRoot, configGroup, FieldDescriptor
                .of(DescriptorUtils.getTypeStringFromDescriptorFormat(rootField.getType()), containingName, Object.class),
                configPhase);
        rootTypesByContainingName.put(containingName, rootInfo);
    }

    private GroupConfigType processConfigGroup(final String containingName, final CompoundConfigType container,
            final boolean consumeSegment, final String baseKey, final Class<?> configGroupClass,
            final AccessorFinder accessorFinder) {
        GroupConfigType gct = new GroupConfigType(containingName, container, consumeSegment, configGroupClass, accessorFinder);
        final Field[] fields = configGroupClass.getDeclaredFields();
        for (Field field : fields) {
            String javadocKey = field.getDeclaringClass().getName().replace("$", ".") + "." + field.getName();
            final int mods = field.getModifiers();
            if (Modifier.isStatic(mods)) {
                // ignore static fields
                continue;
            }
            if (Modifier.isFinal(mods)) {
                // ignore final fields
                continue;
            }
            final ConfigItem configItemAnnotation = field.getAnnotation(ConfigItem.class);
            final String name = configItemAnnotation == null ? hyphenate(field.getName()) : configItemAnnotation.name();
            String subKey;
            boolean consume;
            if (name.equals(ConfigItem.PARENT)) {
                subKey = baseKey;
                consume = false;
            } else if (name.equals(ConfigItem.ELEMENT_NAME)) {
                subKey = baseKey + "." + field.getName();
                consume = true;
            } else if (name.equals(ConfigItem.HYPHENATED_ELEMENT_NAME)) {
                subKey = baseKey + "." + hyphenate(field.getName());
                consume = true;
            } else {
                subKey = baseKey + "." + name;
                consume = true;
            }
            final String defaultValue = configItemAnnotation == null ? ConfigItem.NO_DEFAULT
                    : configItemAnnotation.defaultValue();
            final Type fieldType = field.getGenericType();
            final Class<?> fieldClass = field.getType();
            if (fieldClass.isAnnotationPresent(ConfigGroup.class)) {
                if (!defaultValue.equals(ConfigItem.NO_DEFAULT)) {
                    throw reportError(field, "Unsupported default value");
                }
                gct.addField(processConfigGroup(field.getName(), gct, consume, subKey, fieldClass, accessorFinder));
            } else if (fieldClass.isPrimitive()) {
                final LeafConfigType leaf;
                if (fieldClass == boolean.class) {
                    gct.addField(leaf = new BooleanConfigType(field.getName(), gct, consume,
                            defaultValue.equals(ConfigItem.NO_DEFAULT) ? "false" : defaultValue, javadocKey, subKey));
                } else if (fieldClass == int.class) {
                    gct.addField(leaf = new IntConfigType(field.getName(), gct, consume,
                            defaultValue.equals(ConfigItem.NO_DEFAULT) ? "0" : defaultValue, javadocKey, subKey));
                } else if (fieldClass == long.class) {
                    gct.addField(leaf = new LongConfigType(field.getName(), gct, consume,
                            defaultValue.equals(ConfigItem.NO_DEFAULT) ? "0" : defaultValue, javadocKey, subKey));
                } else if (fieldClass == double.class) {
                    gct.addField(leaf = new DoubleConfigType(field.getName(), gct, consume,
                            defaultValue.equals(ConfigItem.NO_DEFAULT) ? "0" : defaultValue, javadocKey, subKey));
                } else if (fieldClass == float.class) {
                    gct.addField(leaf = new FloatConfigType(field.getName(), gct, consume,
                            defaultValue.equals(ConfigItem.NO_DEFAULT) ? "0" : defaultValue, javadocKey, subKey));
                } else {
                    throw reportError(field, "Unsupported primitive field type");
                }
                container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
            } else if (fieldClass == Map.class) {
                if (rawTypeOfParameter(fieldType, 0) != String.class) {
                    throw reportError(field, "Map key must be " + String.class);
                }
                gct.addField(processMap(field.getName(), gct, field, consume, subKey, typeOfParameter(fieldType, 1),
                        accessorFinder, javadocKey));
            } else if (fieldClass == List.class) {
                // list leaf class
                final LeafConfigType leaf;
                final Class<?> listType = rawTypeOfParameter(fieldType, 0);
                gct.addField(leaf = new ObjectListConfigType(field.getName(), gct, consume,
                        mapDefaultValue(defaultValue, listType), listType, javadocKey, subKey));
                container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
            } else if (fieldClass == Optional.class) {
                final LeafConfigType leaf;
                // optional config property
                gct.addField(leaf = new OptionalObjectConfigType(field.getName(), gct, consume,
                        defaultValue.equals(ConfigItem.NO_DEFAULT) ? "" : defaultValue, rawTypeOfParameter(fieldType, 0),
                        javadocKey, subKey));
                container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
            } else {
                final LeafConfigType leaf;
                // it's a plain config property
                gct.addField(leaf = new ObjectConfigType(field.getName(), gct, consume,
                        mapDefaultValue(defaultValue, fieldClass), fieldClass, javadocKey, subKey));
                container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
            }
        }
        return gct;
    }

    private MapConfigType processMap(final String containingName, final CompoundConfigType container,
            final AnnotatedElement containingElement, final boolean consumeSegment, final String baseKey,
            final Type mapValueType, final AccessorFinder accessorFinder, String javadocKey) {
        MapConfigType mct = new MapConfigType(containingName, container, consumeSegment);
        final Class<?> valueClass = rawTypeOf(mapValueType);
        final String subKey = baseKey + ".{*}";
        if (valueClass == Map.class) {
            if (!(mapValueType instanceof ParameterizedType))
                throw reportError(containingElement, "Map must be parameterized");
            processMap(NO_CONTAINING_NAME, mct, containingElement, true, subKey, typeOfParameter(mapValueType, 1),
                    accessorFinder, javadocKey);
        } else if (valueClass.isAnnotationPresent(ConfigGroup.class)) {
            processConfigGroup(NO_CONTAINING_NAME, mct, true, subKey, valueClass, accessorFinder);
        } else if (valueClass == List.class) {
            if (!(mapValueType instanceof ParameterizedType))
                throw reportError(containingElement, "List must be parameterized");
            final ObjectListConfigType leaf = new ObjectListConfigType(NO_CONTAINING_NAME, mct, consumeSegment, "",
                    rawTypeOfParameter(mapValueType, 0), javadocKey, subKey);
            container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
        } else if (valueClass == Optional.class || valueClass == OptionalInt.class || valueClass == OptionalDouble.class
                || valueClass == OptionalLong.class) {
            throw reportError(containingElement, "Optionals are not allowed as a map value type");
        } else {
            // treat as a plain object
            final ObjectConfigType leaf = new ObjectConfigType(NO_CONTAINING_NAME, mct, true, "", valueClass, javadocKey,
                    subKey);
            container.getConfigDefinition().getLeafPatterns().addPattern(subKey, leaf);
        }
        return mct;
    }

    private String mapDefaultValue(String defaultValue, Class<?> fieldClass) {
        String mappedDefault = defaultValue;
        if (defaultValue.equals(ConfigItem.NO_DEFAULT)) {
            if (Number.class.isAssignableFrom(fieldClass)) {
                mappedDefault = "0";
            } else {
                mappedDefault = "";
            }
        }
        return mappedDefault;
    }

    private static IllegalArgumentException reportError(AnnotatedElement e, String msg) {
        if (e instanceof Member) {
            return new IllegalArgumentException(msg + " at " + e + " of " + ((Member) e).getDeclaringClass());
        } else if (e instanceof Parameter) {
            return new IllegalArgumentException(msg + " at " + e + " of " + ((Parameter) e).getDeclaringExecutable() + " of "
                    + ((Parameter) e).getDeclaringExecutable().getDeclaringClass());
        } else {
            return new IllegalArgumentException(msg + " at " + e);
        }
    }

    public void generateConfigRootClass(ClassOutput classOutput, AccessorFinder accessorFinder) {
        try (ClassCreator cc = ClassCreator.builder().classOutput(classOutput)
                .className(DescriptorUtils.getTypeStringFromDescriptorFormat(rootField.getType())).superClass(Object.class)
                .build()) {
            try (MethodCreator ctor = cc.getMethodCreator("<init>", void.class, SmallRyeConfig.class)) {
                ctor.setModifiers(Opcodes.ACC_PUBLIC);
                final ResultHandle self = ctor.getThis();
                final ResultHandle config = ctor.getMethodParam(0);
                ctor.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), self);
                final ResultHandle cache = ctor.newInstance(ECS_CACHE_CTOR);
                // initialize all fields to defaults
                for (RootInfo value : rootTypesByContainingName.values()) {
                    if (value.getConfigPhase().isAvailableAtRun()) {
                        final CompoundConfigType rootType = value.getRootType();
                        final String containingName = rootType.getContainingName();
                        final FieldDescriptor fieldDescriptor = cc.getFieldCreator(containingName, Object.class)
                                .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL).getFieldDescriptor();
                        ctor.writeInstanceField(fieldDescriptor, self,
                                rootType.writeInitialization(ctor, accessorFinder, cache, config));
                    }
                }
                ctor.returnValue(null);
            }
        }
    }

    public static void loadConfiguration(final ExpandingConfigSource.Cache cache, SmallRyeConfig config,
            final Set<String> unmatched,
            ConfigDefinition... definitions) {
        for (ConfigDefinition definition : definitions) {
            definition.initialize(config, cache);
        }
        outer: for (String propertyName : config.getPropertyNames()) {
            final NameIterator name = new NameIterator(propertyName);
            if (name.hasNext()) {
                if (name.nextSegmentEquals(QUARKUS_NAMESPACE)) {
                    name.next();
                    for (ConfigDefinition definition : definitions) {
                        final LeafConfigType leafType = definition.leafPatterns.match(name);
                        if (leafType != null) {
                            name.goToEnd();
                            leafType.acceptConfigurationValue(name, cache, config);
                            final String nameString = name.toString();
                            definition.loadedProperties.put(nameString, config.getValue(nameString, String.class));
                            continue outer;
                        }
                    }
                    for (String entry : FALSE_POSITIVE_QUARKUS_CONFIG_MISSES) {
                        if (propertyName.equals(entry)) {
                            continue outer;
                        }
                    }
                    log.warnf("Unrecognized configuration key \"%s\" provided", propertyName);
                } else {
                    // non-Quarkus value; capture it in the unmatched map for storage as a default value
                    unmatched.add(propertyName);
                }
            }
        }
    }

    public ConfigPatternMap<LeafConfigType> getLeafPatterns() {
        return leafPatterns;
    }

    public ConfigDefinition getConfigDefinition() {
        return this;
    }

    public TreeMap<String, String> getLoadedProperties() {
        return loadedProperties;
    }

    private void loadFrom(ConfigPatternMap<LeafConfigType> map) {
        final LeafConfigType matched = map.getMatched();
        if (matched != null) {
            matched.load();
        }
        for (String name : map.childNames()) {
            loadFrom(map.getChild(name));
        }
    }

    public Object getRealizedInstance(final Class<?> rootClass) {
        final Object obj = rootObjectsByClass.get(rootClass);
        if (obj == null) {
            throw new IllegalArgumentException("Unknown root class: " + rootClass);
        }
        return obj;
    }

    public RootInfo getInstanceInfo(final Object obj) {
        final ValueInfo valueInfo = realizedInstances.get(obj);
        if (valueInfo == null)
            return null;
        return valueInfo.getRootInfo();
    }

    public static final class RootInfo {
        private final Class<?> rootClass;
        private final GroupConfigType rootType;
        private final FieldDescriptor fieldDescriptor;
        private final ConfigPhase configPhase;

        RootInfo(final Class<?> rootClass, final GroupConfigType rootType, final FieldDescriptor fieldDescriptor,
                final ConfigPhase configPhase) {
            this.rootClass = rootClass;
            this.rootType = rootType;
            this.fieldDescriptor = fieldDescriptor;
            this.configPhase = configPhase;
        }

        public Class<?> getRootClass() {
            return rootClass;
        }

        public GroupConfigType getRootType() {
            return rootType;
        }

        public FieldDescriptor getFieldDescriptor() {
            return fieldDescriptor;
        }

        public ConfigPhase getConfigPhase() {
            return configPhase;
        }
    }

    static final class ValueInfo {
        private final String key;
        private final RootInfo rootInfo;

        ValueInfo(final String key, final RootInfo rootInfo) {
            this.key = key;
            this.rootInfo = rootInfo;
        }

        String getKey() {
            return key;
        }

        RootInfo getRootInfo() {
            return rootInfo;
        }
    }
}
