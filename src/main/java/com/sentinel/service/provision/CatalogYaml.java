package com.sentinel.service.provision;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用 SafeConstructor 读目录，并在构造 Map 之前拒绝重复键。
 * SnakeYAML 1.33 即使关闭 allowDuplicateKeys 也会静默保留后写的键。
 */
final class CatalogYaml {

    private CatalogYaml() {
    }

    static Object load(InputStream input, String source) {
        final String text;
        try {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new CatalogLoadException("读取目录失败: " + source, ex);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(1_000_000);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Node node;
        try {
            node = yaml.compose(new StringReader(text));
        } catch (RuntimeException ex) {
            throw new CatalogLoadException(source + " 不是合法 YAML", ex);
        }
        if (node != null) {
            rejectDuplicateKeys(node, source);
        }
        try {
            return yaml.load(text);
        } catch (RuntimeException ex) {
            throw new CatalogLoadException(source + " 不是合法 YAML", ex);
        }
    }

    private static void rejectDuplicateKeys(Node node, String path) {
        if (node instanceof MappingNode mapping) {
            Set<String> seen = new LinkedHashSet<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    throw new CatalogLoadException(path + " 的键必须是字符串");
                }
                String name = key.getValue();
                if (!seen.add(name)) {
                    throw new CatalogLoadException(path + " 重复的键: " + name);
                }
                rejectDuplicateKeys(tuple.getValueNode(), path + "." + name);
            }
            return;
        }
        if (node instanceof SequenceNode sequence) {
            int index = 0;
            for (Node child : sequence.getValue()) {
                rejectDuplicateKeys(child, path + "[" + index + "]");
                index++;
            }
        }
    }
}
