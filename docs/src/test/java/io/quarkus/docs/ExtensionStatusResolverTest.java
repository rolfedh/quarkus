package io.quarkus.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.docs.generation.ExtensionStatusResolver;
import io.quarkus.docs.generation.ExtensionStatusResolver.ExtensionStatus;

public class ExtensionStatusResolverTest {

    @TempDir
    Path tempDir;

    private ExtensionStatusResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExtensionStatusResolver();
    }

    @Test
    void testStatusFromString() {
        assertEquals(Optional.of(ExtensionStatus.STABLE), ExtensionStatus.fromString("stable"));
        assertEquals(Optional.of(ExtensionStatus.PREVIEW), ExtensionStatus.fromString("preview"));
        assertEquals(Optional.of(ExtensionStatus.EXPERIMENTAL), ExtensionStatus.fromString("experimental"));
        assertEquals(Optional.of(ExtensionStatus.DEPRECATED), ExtensionStatus.fromString("deprecated"));

        // Case insensitive
        assertEquals(Optional.of(ExtensionStatus.STABLE), ExtensionStatus.fromString("STABLE"));
        assertEquals(Optional.of(ExtensionStatus.PREVIEW), ExtensionStatus.fromString("Preview"));

        // Invalid values
        assertTrue(ExtensionStatus.fromString(null).isEmpty());
        assertTrue(ExtensionStatus.fromString("").isEmpty());
        assertTrue(ExtensionStatus.fromString("unknown").isEmpty());
    }

    @Test
    void testMostSignificant() {
        // Experimental is most significant
        assertEquals(ExtensionStatus.EXPERIMENTAL,
                ExtensionStatus.mostSignificant(ExtensionStatus.STABLE, ExtensionStatus.EXPERIMENTAL));
        assertEquals(ExtensionStatus.EXPERIMENTAL,
                ExtensionStatus.mostSignificant(ExtensionStatus.EXPERIMENTAL, ExtensionStatus.STABLE));

        // Preview is more significant than stable
        assertEquals(ExtensionStatus.PREVIEW,
                ExtensionStatus.mostSignificant(ExtensionStatus.STABLE, ExtensionStatus.PREVIEW));

        // Deprecated is more significant than stable
        assertEquals(ExtensionStatus.DEPRECATED,
                ExtensionStatus.mostSignificant(ExtensionStatus.STABLE, ExtensionStatus.DEPRECATED));

        // Experimental is more significant than preview
        assertEquals(ExtensionStatus.EXPERIMENTAL,
                ExtensionStatus.mostSignificant(ExtensionStatus.PREVIEW, ExtensionStatus.EXPERIMENTAL));

        // Null handling
        assertEquals(ExtensionStatus.STABLE, ExtensionStatus.mostSignificant(null, ExtensionStatus.STABLE));
        assertEquals(ExtensionStatus.STABLE, ExtensionStatus.mostSignificant(ExtensionStatus.STABLE, null));
    }

    @Test
    void testScanExtensions() throws IOException {
        // Create a mock extension structure
        Path extensionDir = tempDir.resolve("extensions/my-extension/runtime/src/main/resources/META-INF");
        Files.createDirectories(extensionDir);

        String yaml = """
                ---
                artifact: ${project.groupId}:${project.artifactId}:${project.version}
                name: "My Extension"
                metadata:
                  status: "preview"
                """;
        Files.writeString(extensionDir.resolve("quarkus-extension.yaml"), yaml);

        resolver.scanExtensions(tempDir.resolve("extensions"));

        Optional<ExtensionStatus> status = resolver.getStatus("io.quarkus", "quarkus-my-extension");
        assertTrue(status.isPresent());
        assertEquals(ExtensionStatus.PREVIEW, status.get());
    }

    @Test
    void testScanExtensionsWithStableStatus() throws IOException {
        Path extensionDir = tempDir.resolve("extensions/redis-client/runtime/src/main/resources/META-INF");
        Files.createDirectories(extensionDir);

        String yaml = """
                ---
                artifact: ${project.groupId}:${project.artifactId}:${project.version}
                name: "Redis Client"
                metadata:
                  status: "stable"
                """;
        Files.writeString(extensionDir.resolve("quarkus-extension.yaml"), yaml);

        resolver.scanExtensions(tempDir.resolve("extensions"));

        Optional<ExtensionStatus> status = resolver.getStatus("io.quarkus:quarkus-redis-client");
        assertTrue(status.isPresent());
        assertEquals(ExtensionStatus.STABLE, status.get());
    }

    @Test
    void testScanExtensionsWithNoStatus() throws IOException {
        Path extensionDir = tempDir.resolve("extensions/no-status/runtime/src/main/resources/META-INF");
        Files.createDirectories(extensionDir);

        String yaml = """
                ---
                artifact: ${project.groupId}:${project.artifactId}:${project.version}
                name: "No Status Extension"
                metadata:
                  keywords:
                    - "test"
                """;
        Files.writeString(extensionDir.resolve("quarkus-extension.yaml"), yaml);

        resolver.scanExtensions(tempDir.resolve("extensions"));

        Optional<ExtensionStatus> status = resolver.getStatus("io.quarkus:quarkus-no-status");
        assertFalse(status.isPresent());
    }

    @Test
    void testComputeEffectiveStatus() throws IOException {
        // Create multiple extensions with different statuses
        createExtension("stable-ext", "stable");
        createExtension("preview-ext", "preview");
        createExtension("experimental-ext", "experimental");

        resolver.scanExtensions(tempDir.resolve("extensions"));

        // Single extension
        assertEquals(Optional.of(ExtensionStatus.STABLE),
                resolver.computeEffectiveStatus("io.quarkus:quarkus-stable-ext"));

        // Multiple extensions - should return most significant
        assertEquals(Optional.of(ExtensionStatus.PREVIEW),
                resolver.computeEffectiveStatus("io.quarkus:quarkus-stable-ext,io.quarkus:quarkus-preview-ext"));

        assertEquals(Optional.of(ExtensionStatus.EXPERIMENTAL),
                resolver.computeEffectiveStatus(
                        "io.quarkus:quarkus-stable-ext,io.quarkus:quarkus-preview-ext,io.quarkus:quarkus-experimental-ext"));
    }

    @Test
    void testComputeEffectiveStatusWithUnknownExtension() throws IOException {
        createExtension("known-ext", "stable");
        resolver.scanExtensions(tempDir.resolve("extensions"));

        // Mix of known and unknown extensions
        assertEquals(Optional.of(ExtensionStatus.STABLE),
                resolver.computeEffectiveStatus("io.quarkus:quarkus-known-ext,io.quarkus:quarkus-unknown-ext"));

        // Only unknown extensions
        assertTrue(resolver.computeEffectiveStatus("io.quarkus:quarkus-unknown-ext").isEmpty());
    }

    @Test
    void testEmptyExtensionsList() {
        assertTrue(resolver.computeEffectiveStatus(null).isEmpty());
        assertTrue(resolver.computeEffectiveStatus("").isEmpty());
        assertTrue(resolver.computeEffectiveStatus("   ").isEmpty());
    }

    private void createExtension(String name, String status) throws IOException {
        Path extensionDir = tempDir.resolve("extensions/" + name + "/runtime/src/main/resources/META-INF");
        Files.createDirectories(extensionDir);

        String yaml = """
                ---
                artifact: ${project.groupId}:${project.artifactId}:${project.version}
                name: "%s"
                metadata:
                  status: "%s"
                """.formatted(name, status);
        Files.writeString(extensionDir.resolve("quarkus-extension.yaml"), yaml);
    }

    @Test
    void testScanRealExtensionsDirectory() throws IOException {
        // This test uses the actual extensions directory if available
        Path realExtensionsDir = Path.of("../extensions");
        if (!Files.isDirectory(realExtensionsDir)) {
            return; // Skip if not running from docs directory
        }

        ExtensionStatusResolver realResolver = new ExtensionStatusResolver()
                .scanExtensions(realExtensionsDir);

        // Verify some known extensions
        Optional<ExtensionStatus> redisStatus = realResolver.getStatus("io.quarkus:quarkus-redis-client");
        if (redisStatus.isPresent()) {
            assertEquals(ExtensionStatus.STABLE, redisStatus.get());
        }

        Optional<ExtensionStatus> hibernateReactiveStatus = realResolver.getStatus("io.quarkus:quarkus-hibernate-reactive");
        if (hibernateReactiveStatus.isPresent()) {
            assertEquals(ExtensionStatus.PREVIEW, hibernateReactiveStatus.get());
        }
    }
}
