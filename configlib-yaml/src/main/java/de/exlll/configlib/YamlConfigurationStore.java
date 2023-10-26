package de.exlll.configlib;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.representer.StandardRepresenter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static de.exlll.configlib.Validator.requireNonNull;

/**
 * A configuration store that saves and loads configurations as YAML text files.
 *
 * @param <T> the configuration type
 */
@SuppressWarnings("unchecked")
public final class YamlConfigurationStore<T> implements FileConfigurationStore<T> {
    private static final Dump YAML_DUMPER = newYamlDumper();
    private static final Load YAML_LOADER = newYamlLoader();
    private final YamlConfigurationProperties properties;
    private final TypeSerializer<T, ?> serializer;
    private final CommentNodeExtractor extractor;

    /**
     * Constructs a new store.
     *
     * @param configurationType the type of configuration
     * @param properties        the properties
     * @throws NullPointerException if any argument is null
     */
    public YamlConfigurationStore(Class<T> configurationType, YamlConfigurationProperties properties) {
        requireNonNull(configurationType, "configuration type");
        this.properties = requireNonNull(properties, "properties");
        this.serializer = TypeSerializer.newSerializerFor(configurationType, properties);
        this.extractor = new CommentNodeExtractor(properties);
    }

    @Override
    public void save(T configuration, Path configurationFile) {
        requireNonNull(configuration, "configuration");
        requireNonNull(configurationFile, "configuration file");
        tryCreateParentDirectories(configurationFile);
        var extractedCommentNodes = extractor.extractCommentNodes(configuration);
        var yamlFileWriter = new YamlFileWriter(configurationFile, properties);
        var dumpedYaml = tryDump(configuration);
        yamlFileWriter.writeYaml(dumpedYaml, extractedCommentNodes);
    }

    private void tryCreateParentDirectories(Path configurationFile) {
        Path parent = configurationFile.getParent();
        if (!Files.exists(parent) && properties.createParentDirectories()) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String tryDump(T configuration) {
        final Map<?, ?> serializedConfiguration = serializer.serialize(configuration);
        try {
            return YAML_DUMPER.dumpToString(flattenAndSortKeys((Map<String, Object>) serializedConfiguration));
        } catch (YamlEngineException e) {
            String msg = "The given configuration could not be converted into YAML. \n" +
                    "Do all custom serializers produce valid target types?";
            throw new ConfigurationException(msg, e);
        }
    }

    /**
     * Modifies the input map by flattening nested keys and sorting them alphabetically.
     *
     * @param inputMap the input map to modify
     * @return the modified map with flattened and sorted keys
     */
    public Map<String, Object> flattenAndSortKeys(Map<String, Object> inputMap) {

        final Comparator<String> comparator = createComparator("");

        final Map<String, Object> outputMap = new TreeMap<>(comparator);

        for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key.contains(".")) {
                String[] keys = key.split("\\.");

                Map<String, Object> nestedMap = outputMap;

                for (int i = 0; i < keys.length - 1; i++) {
                    String subKey = keys[i];
                    nestedMap = getOrCreateNestedMap(nestedMap, subKey, key);
                }

                nestedMap.put(keys[keys.length - 1], value);
            } else {
                outputMap.put(key, value);
            }
        }


        return outputMap;
    }

    private Comparator<String> createComparator(String keys) {
        return (o1, o2) -> {
            Optional<Field> annValue1 = getFieldFromAnnotationValue(keys + "." + o1);
            Optional<Field> annValue2 = getFieldFromAnnotationValue(keys + "." + o2);


            if (annValue1.isEmpty() && annValue2.isEmpty()) {
                return o1.compareTo(o2);
            } else {
                return annValue1.map(value -> annValue2.map(field -> Integer.compare(getIndex(value), getIndex(field))).orElse(-1)).orElse(1);
            }
        };
    }

    private int getIndex(Field field) {
        return List.of(serializer.getType().getDeclaredFields()).indexOf(field);
    }

    private Optional<Field> getFieldFromAnnotationValue(String annotationValue) {
        return Arrays.stream(serializer.getType().getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(de.exlll.configlib.Path.class))
                .filter(f -> f.getAnnotation(de.exlll.configlib.Path.class).value().equals(annotationValue))
                .findFirst();
    }

    private Map<String, Object> getOrCreateNestedMap(Map<String, Object> map, String key, String fullKey) {
        if (map.containsKey(key) && map.get(key) instanceof Map) {
            return (Map<String, Object>) map.get(key);
        } else {
            Map<String, Object> newMap = new TreeMap<>(createComparator(fullKey.replaceFirst("\\." + key, "")));
            map.put(key, newMap);
            return newMap;
        }
    }

    @Override
    public T load(Path configurationFile) {
        requireNonNull(configurationFile, "configuration file");
        try (var reader = Files.newBufferedReader(configurationFile)) {
            var yaml = YAML_LOADER.loadFromReader(reader);
            var conf = requireYamlMap(yaml, configurationFile);
            return serializer.deserialize(findPossibles(convert((Map<String, Object>) conf)));
        } catch (YamlEngineException e) {
            String msg = "The configuration file at %s does not contain valid YAML.";
            throw new ConfigurationException(msg.formatted(configurationFile), e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a nested map to a flat map.
     *
     * @param toFlat the nested map to flatten
     * @return the flattened map
     */
    private Map<String, Object> convert(Map<String, Object> toFlat) {
        final Map<String, Object> flatMap = new LinkedHashMap<>();

        flattenMap(toFlat, "", flatMap);

        return flatMap;
    }

    /**
     * Converts a nested map to a flat map.
     *
     * @param originalMap the nested map to flatten
     * @param currentPath the current path of the nested map (used for building the keys in the flat map)
     * @param outputMap   the flattened map where the key-value pairs will be stored
     */
    public static void flattenMap(Map<String, Object> originalMap, String currentPath, Map<String, Object> outputMap) {
        final String pathPrefix = currentPath.isEmpty() ? "" : currentPath + ".";

        for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
            if (entry.getValue() instanceof Map) {
                // If the value is a nested map, recursively call this function
                flattenMap((Map<String, Object>) entry.getValue(), pathPrefix + entry.getKey(), outputMap);
            } else {
                // Otherwise, put the value into the output map with the fully built path as the key
                outputMap.put(pathPrefix + entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Finds possible configurations from the input map based on the annotations defined in the serialized class.
     *
     * @param input the input map containing the configuration values
     * @return a map of possible configurations extracted from the input map
     */
    private Map<String, Object> findPossibles(Map<String, Object> input) {
        final Class<?> clazz = serializer.getType();

        final List<Field> fields = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(de.exlll.configlib.Path.class))
                .toList();

        final Map<String, Object> flatMap = new LinkedHashMap<>();

        for (Field field : fields) {

            final String annotationValue = field.getAnnotation(de.exlll.configlib.Path.class).value();

            for (String key : input.keySet()) {


                if (key.startsWith(annotationValue)
                        && key.replace(annotationValue, "").startsWith(".")
                ) {

                    final String finalKey = key.replace(annotationValue, "").replaceFirst("\\.", "");

                    final Map<String, Object> nestedMap = (Map<String, Object>) flatMap.getOrDefault(annotationValue, new LinkedHashMap<>());

                    fixValue(nestedMap, finalKey, key, input);

                    flatMap.put(annotationValue, nestedMap);

                } else if (key.equals(annotationValue)) {
                    flatMap.put(annotationValue, input.get(key));
                }
            }
        }

        return flatMap;
    }

    /**
     * Fixes the value in the nested map by traversing through the input map and updating the corresponding keys.
     *
     * @param nested      the nested map to update
     * @param key         the current key to be updated
     * @param completeKey the complete key of the current value in the input map
     * @param input       the input map containing the values
     */
    private void fixValue(Map<String, Object> nested, String key, String completeKey, Map<String, Object> input) {
        final Object element = input.get(completeKey);

        if (element instanceof Map) {
            final Map<String, Object> map = (Map<String, Object>) element;
            for (String mapKey : map.keySet()) {
                fixValue(nested, mapKey, completeKey + "." + mapKey, input);
            }

            nested.put(key, map);
        } else {

            if (key.contains(".")) {
                final String[] keys = key.split("\\.", 2);
                final String firstKey = keys[0];
                final String secondKey = keys[1];
                final Map<String, Object> nestedMap = (Map<String, Object>) nested.getOrDefault(firstKey, new LinkedHashMap<>());

                nested.put(firstKey, nestedMap);

                fixValue(nestedMap, secondKey, completeKey, input);
            } else {
                nested.put(key, element);
            }

        }
    }

    private Map<?, ?> requireYamlMap(Object yaml, Path configurationFile) {
        if (yaml == null) {
            String msg = "The configuration file at %s is empty or only contains null.";
            throw new ConfigurationException(msg.formatted(configurationFile));
        }

        if (!(yaml instanceof Map<?, ?>)) {
            String msg = "The contents of the YAML file at %s do not represent a configuration. " +
                    "A valid configuration file contains a YAML map but instead a " +
                    "'" + yaml.getClass() + "' was found.";
            throw new ConfigurationException(msg.formatted(configurationFile));
        }

        return (Map<?, ?>) yaml;
    }

    @Override
    public T update(Path configurationFile) {
        requireNonNull(configurationFile, "configuration file");
        if (Files.exists(configurationFile)) {
            T configuration = load(configurationFile);
            save(configuration, configurationFile);
            return configuration;
        }
        T defaultConfiguration = serializer.newDefaultInstance();
        save(defaultConfiguration, configurationFile);
        return defaultConfiguration;
    }

    static Dump newYamlDumper() {
        DumpSettings settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(2)
                .build();
        return new Dump(settings, new YamlConfigurationRepresenter(settings));
    }

    static Load newYamlLoader() {
        LoadSettings settings = LoadSettings.builder().setAllowRecursiveKeys(true).build();
        return new Load(settings);
    }

    /**
     * A writer that writes YAML to a file.
     */
    static final class YamlFileWriter {
        private final Path configurationFile;
        private final YamlConfigurationProperties properties;
        private BufferedWriter writer;

        YamlFileWriter(Path configurationFile, YamlConfigurationProperties properties) {
            this.configurationFile = requireNonNull(configurationFile, "configuration file");
            this.properties = requireNonNull(properties, "configuration properties");
        }

        public void writeYaml(String yaml, Queue<CommentNode> nodes) {
            try (BufferedWriter writer = Files.newBufferedWriter(configurationFile)) {
                this.writer = writer;
                writeHeader();
                writeContent(yaml, nodes);
                writeFooter();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                this.writer = null;
            }
        }

        private void writeHeader() throws IOException {
            if (properties.getHeader() != null) {
                writeAsComment(properties.getHeader());
                writer.newLine();
            }
        }

        private void writeFooter() throws IOException {
            if (properties.getFooter() != null) {
                writer.newLine();
                writeAsComment(properties.getFooter());
            }
        }

        private void writeAsComment(String comment) throws IOException {
            String[] lines = comment.split("\n");
            writeComments(Arrays.asList(lines), 0);
        }

        private void writeComments(List<String> comments, int indentLevel) throws IOException {
            String indent = "  ".repeat(indentLevel);
            for (String comment : comments) {
                if (comment.isEmpty()) {
                    writer.newLine();
                    continue;
                }
                String line = indent + "# " + comment;
                writeLine(line);
            }
        }

        private void writeLine(String line) throws IOException {
            writer.write(line);
            writer.newLine();
        }

        private void writeContent(String yaml, Queue<CommentNode> nodes) throws IOException {
            if (nodes.isEmpty()) {
                writer.write(yaml);
            } else {
                writeCommentedYaml(yaml, nodes);
            }
        }

        private void writeCommentedYaml(String yaml, Queue<CommentNode> nodes)
                throws IOException {
            /*
             * The following algorithm is necessary since no Java YAML library seems
             * to properly support comments, at least not the way I want them.
             *
             * The algorithm writes YAML line by line and keeps track of the current
             * context with the help of elementNames lists which come from the nodes in
             * the 'nodes' queue. The 'nodes' queue contains nodes in the order in
             * which fields and records components were extracted, which happened in
             * DFS manner and with fields of a parent class being read before the fields
             * of a child. That order ultimately represents the order in which the
             * YAML file is structured.
             */
            var node = nodes.poll();
            var currentIndentLevel = 0;

            for (final String line : yaml.split("\n")) {
                if (node == null) {
                    writeLine(line);
                    continue;
                }

                final var elementNames = node.elementNames();
                final var indent = "  ".repeat(currentIndentLevel);

                final var lineStart = indent + elementNames.get(currentIndentLevel) + ":";
                if (!line.startsWith(lineStart)) {
                    writeLine(line);
                    continue;
                }

                final var commentIndentLevel = elementNames.size() - 1;
                if (currentIndentLevel++ == commentIndentLevel) {
                    writeComments(node.comments(), commentIndentLevel);
                    if ((node = nodes.poll()) != null) {
                        currentIndentLevel = lengthCommonPrefix(node.elementNames(), elementNames);
                    }
                }

                writeLine(line);
            }
        }

        static int lengthCommonPrefix(List<String> l1, List<String> l2) {
            final int maxLen = Math.min(l1.size(), l2.size());
            int result = 0;
            for (int i = 0; i < maxLen; i++) {
                String s1 = l1.get(i);
                String s2 = l2.get(i);
                if (s1.equals(s2))
                    result++;
                else return result;
            }
            return result;
        }
    }

    /**
     * A custom representer that prevents aliasing.
     */
    static final class YamlConfigurationRepresenter extends StandardRepresenter {
        public YamlConfigurationRepresenter(DumpSettings settings) {
            super(settings);
        }

        @Override
        protected Node representSequence(Tag tag, Iterable<?> sequence, FlowStyle flowStyle) {
            Node node = super.representSequence(tag, sequence, flowStyle);
            representedObjects.clear();
            return node;
        }

        @Override
        protected Node representMapping(Tag tag, Map<?, ?> mapping, FlowStyle flowStyle) {
            Node node = super.representMapping(tag, mapping, flowStyle);
            representedObjects.clear();
            return node;
        }
    }
}
