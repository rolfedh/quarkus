package io.quarkus.docs.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Resolves extension status from quarkus-extension.yaml files in the extensions directory.
 * <p>
 * This class scans the extensions directory structure to build a mapping from extension
 * artifact coordinates (groupId:artifactId) to their declared status (stable, preview,
 * experimental, deprecated).
 */
public class ExtensionStatusResolver {

    private static final String EXTENSION_YAML = "quarkus-extension.yaml";
    private static final String META_INF = "META-INF";
    private static final String RESOURCES = "resources";
    private static final String MAIN = "main";
    private static final String SRC = "src";
    private static final String RUNTIME = "runtime";

    private final Map<String, ExtensionStatus> extensionStatuses;
    private final ObjectMapper yamlMapper;

    public enum ExtensionStatus {
        STABLE("stable", 0),
        PREVIEW("preview", 1),
        EXPERIMENTAL("experimental", 2),
        DEPRECATED("deprecated", 3);

        private final String id;
        private final int priority;

        ExtensionStatus(String id, int priority) {
            this.id = id;
            this.priority = priority;
        }

        public String getId() {
            return id;
        }

        /**
         * Priority for determining the "most significant" status when multiple extensions
         * are involved. Higher priority means the status is more significant and should
         * take precedence.
         */
        public int getPriority() {
            return priority;
        }

        public static Optional<ExtensionStatus> fromString(String status) {
            if (status == null || status.isBlank()) {
                return Optional.empty();
            }
            String normalized = status.toLowerCase().trim();
            for (ExtensionStatus s : values()) {
                if (s.id.equals(normalized)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }

        /**
         * Returns the more significant status between two statuses.
         * Experimental > Preview > Deprecated > Stable
         */
        public static ExtensionStatus mostSignificant(ExtensionStatus a, ExtensionStatus b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.priority >= b.priority ? a : b;
        }
    }

    public ExtensionStatusResolver() {
        this.extensionStatuses = new HashMap<>();
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Scans the extensions directory and builds the extension status map.
     *
     * @param extensionsDir the root extensions directory (e.g., /path/to/quarkus/extensions)
     * @return this resolver for method chaining
     * @throws IOException if an I/O error occurs while scanning
     */
    public ExtensionStatusResolver scanExtensions(Path extensionsDir) throws IOException {
        if (!Files.isDirectory(extensionsDir)) {
            System.err.println("[WARN] Extensions directory does not exist: " + extensionsDir);
            return this;
        }

        System.out.println("[INFO] Scanning extensions directory: " + extensionsDir);

        try (Stream<Path> walker = Files.walk(extensionsDir, 10)) {
            walker.filter(this::isExtensionYaml)
                    .forEach(this::parseExtensionYaml);
        }

        System.out.println("[INFO] Found " + extensionStatuses.size() + " extensions with status information");
        return this;
    }

    private boolean isExtensionYaml(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        // Match pattern: extensions/**/runtime/src/main/resources/META-INF/quarkus-extension.yaml
        String pathStr = path.toString();
        return pathStr.endsWith(EXTENSION_YAML)
                && path.getParent() != null
                && path.getParent().getFileName().toString().equals(META_INF)
                && pathStr.contains(RUNTIME);
    }

    private void parseExtensionYaml(Path yamlPath) {
        try {
            JsonNode root = yamlMapper.readTree(yamlPath.toFile());

            // Extract artifact coordinates
            JsonNode artifactNode = root.get("artifact");
            if (artifactNode == null) {
                return;
            }

            String artifact = artifactNode.asText();
            // Artifact format: ${project.groupId}:${project.artifactId}:${project.version}
            // or actual coordinates like io.quarkus:quarkus-redis-client:999-SNAPSHOT
            // We need to derive the actual groupId:artifactId from the path

            String artifactId = deriveArtifactId(yamlPath);
            if (artifactId == null) {
                return;
            }

            // Default groupId for Quarkus core extensions
            String groupId = "io.quarkus";
            String gav = groupId + ":" + artifactId;

            // Extract status from metadata
            JsonNode metadataNode = root.get("metadata");
            if (metadataNode != null) {
                JsonNode statusNode = metadataNode.get("status");
                if (statusNode != null) {
                    String statusStr = statusNode.asText();
                    ExtensionStatus.fromString(statusStr).ifPresent(status -> {
                        extensionStatuses.put(gav, status);
                    });
                }
            }

        } catch (IOException e) {
            System.err.println("[WARN] Failed to parse extension YAML: " + yamlPath + " - " + e.getMessage());
        }
    }

    /**
     * Derives the artifactId from the extension YAML path.
     * The path structure is: extensions/{extension-name}/runtime/src/main/resources/META-INF/quarkus-extension.yaml
     * or: extensions/{extension-name}/{sub-module}/runtime/src/main/resources/META-INF/quarkus-extension.yaml
     */
    private String deriveArtifactId(Path yamlPath) {
        // Navigate up to find the runtime module directory
        Path current = yamlPath.getParent(); // META-INF
        if (current == null) return null;
        current = current.getParent(); // resources
        if (current == null) return null;
        current = current.getParent(); // main
        if (current == null) return null;
        current = current.getParent(); // src
        if (current == null) return null;
        current = current.getParent(); // runtime
        if (current == null) return null;

        // Now current is the runtime directory
        Path runtimeDir = current;
        Path extensionDir = runtimeDir.getParent();
        if (extensionDir == null) return null;

        // Build the artifact ID based on the directory structure
        StringBuilder artifactId = new StringBuilder("quarkus-");

        // Check if this is a nested extension (e.g., extensions/container-image/container-image-docker)
        Path extensionsRoot = extensionDir.getParent();
        if (extensionsRoot != null && !extensionsRoot.getFileName().toString().equals("extensions")) {
            // This is a nested extension
            Path parentExtensionDir = extensionsRoot;
            Path grandParent = parentExtensionDir.getParent();
            if (grandParent != null && grandParent.getFileName().toString().equals("extensions")) {
                // Pattern: extensions/parent/child/runtime
                artifactId.append(extensionDir.getFileName().toString());
            } else {
                // Deeper nesting or different structure
                artifactId.append(extensionDir.getFileName().toString());
            }
        } else {
            // Simple extension: extensions/extension-name/runtime
            artifactId.append(extensionDir.getFileName().toString());
        }

        return artifactId.toString();
    }

    /**
     * Gets the status for a specific extension.
     *
     * @param groupId    the extension's group ID (e.g., "io.quarkus")
     * @param artifactId the extension's artifact ID (e.g., "quarkus-redis-client")
     * @return the extension status, or empty if not found or no status declared
     */
    public Optional<ExtensionStatus> getStatus(String groupId, String artifactId) {
        String gav = groupId + ":" + artifactId;
        return Optional.ofNullable(extensionStatuses.get(gav));
    }

    /**
     * Gets the status for a specific extension using the full GAV string.
     *
     * @param gav the extension's group:artifact coordinates (e.g., "io.quarkus:quarkus-redis-client")
     * @return the extension status, or empty if not found or no status declared
     */
    public Optional<ExtensionStatus> getStatus(String gav) {
        // Handle full GAV with version (groupId:artifactId:version)
        String[] parts = gav.split(":");
        if (parts.length >= 2) {
            String normalizedGav = parts[0] + ":" + parts[1];
            return Optional.ofNullable(extensionStatuses.get(normalizedGav));
        }
        return Optional.ofNullable(extensionStatuses.get(gav));
    }

    /**
     * Returns an unmodifiable view of all extension statuses.
     */
    public Map<String, ExtensionStatus> getAllStatuses() {
        return Collections.unmodifiableMap(extensionStatuses);
    }

    /**
     * Computes the effective status for a list of extensions.
     * Uses the "most significant" status among all extensions.
     *
     * @param extensions comma-separated list of extension GAVs
     * @return the most significant status, or empty if no extensions have status
     */
    public Optional<ExtensionStatus> computeEffectiveStatus(String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return Optional.empty();
        }

        ExtensionStatus result = null;
        for (String ext : extensions.split(",")) {
            String trimmed = ext.trim();
            Optional<ExtensionStatus> status = getStatus(trimmed);
            if (status.isPresent()) {
                result = ExtensionStatus.mostSignificant(result, status.get());
            }
        }

        return Optional.ofNullable(result);
    }

    /**
     * For testing and debugging: prints all resolved extensions.
     */
    public void printAllStatuses() {
        extensionStatuses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println("  " + entry.getKey() + " -> " + entry.getValue().getId()));
    }
}
