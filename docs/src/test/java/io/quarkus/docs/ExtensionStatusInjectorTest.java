package io.quarkus.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.docs.generation.ExtensionStatusInjector;
import io.quarkus.docs.generation.ExtensionStatusInjector.InjectionResult;
import io.quarkus.docs.generation.ExtensionStatusResolver;

public class ExtensionStatusInjectorTest {

    @TempDir
    Path tempDir;

    private Path extensionsDir;
    private Path asciidocDir;
    private ExtensionStatusResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        extensionsDir = tempDir.resolve("extensions");
        asciidocDir = tempDir.resolve("asciidoc");
        Files.createDirectories(extensionsDir);
        Files.createDirectories(asciidocDir);

        // Create some test extensions
        createExtension("stable-ext", "stable");
        createExtension("preview-ext", "preview");
        createExtension("experimental-ext", "experimental");

        resolver = new ExtensionStatusResolver().scanExtensions(extensionsDir);
    }

    @Test
    void testInjectStatusIntoGuideWithoutStatus() throws IOException {
        String guideContent = """
                ////
                This guide is maintained in the main Quarkus repository
                ////
                = My Guide
                include::_attributes.adoc[]
                :categories: data
                :extensions: io.quarkus:quarkus-preview-ext

                This is the preamble.
                """;
        Path guidePath = asciidocDir.resolve("my-guide.adoc");
        Files.writeString(guidePath, guideContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        Map<String, InjectionResult> results = injector.getResults();
        assertEquals(InjectionResult.Type.INJECTED, results.get("my-guide.adoc").type);

        // Verify the file was updated
        String updatedContent = Files.readString(guidePath);
        assertTrue(updatedContent.contains(":extension-status: preview"));
    }

    @Test
    void testInjectStatusAfterIncludeAttributes() throws IOException {
        String guideContent = """
                = My Guide
                include::_attributes.adoc[]
                :categories: data
                :extensions: io.quarkus:quarkus-stable-ext

                Preamble text.
                """;
        Path guidePath = asciidocDir.resolve("test-guide.adoc");
        Files.writeString(guidePath, guideContent);

        new ExtensionStatusInjector(resolver).processDirectory(asciidocDir);

        String updatedContent = Files.readString(guidePath);
        // Status should be inserted after include::_attributes.adoc[]
        int includePos = updatedContent.indexOf("include::_attributes.adoc[]");
        int statusPos = updatedContent.indexOf(":extension-status: stable");
        assertTrue(statusPos > includePos, "Status should be after include::_attributes.adoc[]");
    }

    @Test
    void testSkipGuideWithNoExtensions() throws IOException {
        String guideContent = """
                = My Guide
                include::_attributes.adoc[]
                :categories: data

                No extensions attribute here.
                """;
        Path guidePath = asciidocDir.resolve("no-extensions.adoc");
        Files.writeString(guidePath, guideContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        Map<String, InjectionResult> results = injector.getResults();
        assertEquals(InjectionResult.Type.NO_EXTENSIONS, results.get("no-extensions.adoc").type);

        // File should not be modified
        String content = Files.readString(guidePath);
        assertFalse(content.contains(":extension-status:"));
    }

    @Test
    void testPreserveCorrectExistingStatus() throws IOException {
        String guideContent = """
                = My Guide
                :extension-status: preview
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-preview-ext

                Preamble.
                """;
        Path guidePath = asciidocDir.resolve("correct-status.adoc");
        Files.writeString(guidePath, guideContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        Map<String, InjectionResult> results = injector.getResults();
        assertEquals(InjectionResult.Type.ALREADY_CORRECT, results.get("correct-status.adoc").type);
    }

    @Test
    void testUpdateMismatchedStatus() throws IOException {
        String guideContent = """
                = My Guide
                :extension-status: stable
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-experimental-ext

                Preamble.
                """;
        Path guidePath = asciidocDir.resolve("mismatched-status.adoc");
        Files.writeString(guidePath, guideContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        Map<String, InjectionResult> results = injector.getResults();
        assertEquals(InjectionResult.Type.MISMATCH, results.get("mismatched-status.adoc").type);

        // File should be updated
        String updatedContent = Files.readString(guidePath);
        assertTrue(updatedContent.contains(":extension-status: experimental"));
        assertFalse(updatedContent.contains(":extension-status: stable"));
    }

    @Test
    void testDryRunMode() throws IOException {
        String originalContent = """
                = My Guide
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-preview-ext

                Preamble.
                """;
        Path guidePath = asciidocDir.resolve("dry-run-test.adoc");
        Files.writeString(guidePath, originalContent);

        new ExtensionStatusInjector(resolver)
                .setDryRun(true)
                .processDirectory(asciidocDir);

        // File should NOT be modified in dry-run mode
        String content = Files.readString(guidePath);
        assertEquals(originalContent, content);
    }

    @Test
    void testMultipleExtensionsMostSignificantStatus() throws IOException {
        String guideContent = """
                = Multi Extension Guide
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-stable-ext,io.quarkus:quarkus-preview-ext,io.quarkus:quarkus-experimental-ext

                This guide covers multiple extensions.
                """;
        Path guidePath = asciidocDir.resolve("multi-ext.adoc");
        Files.writeString(guidePath, guideContent);

        new ExtensionStatusInjector(resolver).processDirectory(asciidocDir);

        String updatedContent = Files.readString(guidePath);
        // Should use the most significant status (experimental)
        assertTrue(updatedContent.contains(":extension-status: experimental"));
    }

    @Test
    void testSkipTemplateFiles() throws IOException {
        String templateContent = """
                = Template
                include::_attributes.adoc[]
                :extension-status: preview
                :extensions: io.quarkus:quarkus-preview-ext

                Template content.
                """;
        Path templatePath = asciidocDir.resolve("_template.adoc");
        Files.writeString(templatePath, templateContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        // Template files (starting with _) should be skipped
        assertFalse(injector.getResults().containsKey("_template.adoc"));
    }

    @Test
    void testSkipReadmeFile() throws IOException {
        String readmeContent = """
                = README
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-preview-ext

                Readme content.
                """;
        Path readmePath = asciidocDir.resolve("README.adoc");
        Files.writeString(readmePath, readmeContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        // README should be skipped
        assertFalse(injector.getResults().containsKey("README.adoc"));
    }

    @Test
    void testHandleQuotedStatus() throws IOException {
        String guideContent = """
                = My Guide
                :extension-status: "experimental"
                include::_attributes.adoc[]
                :extensions: io.quarkus:quarkus-experimental-ext

                Preamble.
                """;
        Path guidePath = asciidocDir.resolve("quoted-status.adoc");
        Files.writeString(guidePath, guideContent);

        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .processDirectory(asciidocDir);

        Map<String, InjectionResult> results = injector.getResults();
        assertEquals(InjectionResult.Type.ALREADY_CORRECT, results.get("quoted-status.adoc").type);
    }

    private void createExtension(String name, String status) throws IOException {
        Path extensionDir = extensionsDir.resolve(name + "/runtime/src/main/resources/META-INF");
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
}
