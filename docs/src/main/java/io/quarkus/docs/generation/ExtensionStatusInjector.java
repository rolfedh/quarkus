package io.quarkus.docs.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.quarkus.docs.generation.ExtensionStatusResolver.ExtensionStatus;

/**
 * Injects extension status attributes into AsciiDoc guide files based on the extensions
 * they reference.
 * <p>
 * This tool:
 * <ul>
 * <li>Scans AsciiDoc files for the {@code :extensions:} attribute</li>
 * <li>Looks up the status of each referenced extension</li>
 * <li>Computes an effective status (most significant among all extensions)</li>
 * <li>Injects or updates the {@code :extension-status:} attribute</li>
 * </ul>
 * <p>
 * The injection only occurs if:
 * <ul>
 * <li>The guide has an {@code :extensions:} attribute</li>
 * <li>No {@code :extension-status:} attribute is present, OR</li>
 * <li>The existing status differs from the computed status (with a warning)</li>
 * </ul>
 */
public class ExtensionStatusInjector {

    // Pattern to match :extensions: attribute in document header
    private static final Pattern EXTENSIONS_PATTERN = Pattern.compile(
            "^:extensions:\\s*(.+)$", Pattern.MULTILINE);

    // Pattern to match existing :extension-status: attribute
    private static final Pattern EXTENSION_STATUS_PATTERN = Pattern.compile(
            "^:extension-status:\\s*\"?([^\"\\n]+)\"?\\s*$", Pattern.MULTILINE);

    // Pattern to find the include::_attributes.adoc[] line
    private static final Pattern INCLUDE_ATTRIBUTES_PATTERN = Pattern.compile(
            "^include::_attributes\\.adoc\\[]\\s*$", Pattern.MULTILINE);

    private final ExtensionStatusResolver resolver;
    private final Map<String, InjectionResult> results = new HashMap<>();

    private boolean dryRun = false;
    private boolean verbose = false;

    public ExtensionStatusInjector(ExtensionStatusResolver resolver) {
        this.resolver = resolver;
    }

    public ExtensionStatusInjector setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public ExtensionStatusInjector setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /**
     * Process all AsciiDoc files in the given directory.
     *
     * @param asciidocDir the directory containing AsciiDoc files
     * @return this injector for method chaining
     * @throws IOException if an I/O error occurs
     */
    public ExtensionStatusInjector processDirectory(Path asciidocDir) throws IOException {
        if (!Files.isDirectory(asciidocDir)) {
            throw new IllegalArgumentException("Not a directory: " + asciidocDir);
        }

        System.out.println("[INFO] Processing AsciiDoc files in: " + asciidocDir);

        try (Stream<Path> files = Files.list(asciidocDir)) {
            files.filter(this::isGuideFile)
                    .forEach(this::processFile);
        }

        return this;
    }

    private boolean isGuideFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String filename = path.getFileName().toString();
        // Skip templates, includes, and other special files
        return filename.endsWith(".adoc")
                && !filename.startsWith("_")
                && !filename.equals("README.adoc");
    }

    private void processFile(Path path) {
        try {
            String content = Files.readString(path);
            String filename = path.getFileName().toString();

            // Check if this file has an :extensions: attribute
            Matcher extensionsMatcher = EXTENSIONS_PATTERN.matcher(content);
            if (!extensionsMatcher.find()) {
                if (verbose) {
                    System.out.println("[DEBUG] No :extensions: attribute in: " + filename);
                }
                results.put(filename, InjectionResult.noExtensions());
                return;
            }

            String extensions = extensionsMatcher.group(1).trim();

            // Compute effective status from extensions
            Optional<ExtensionStatus> computedStatus = resolver.computeEffectiveStatus(extensions);
            if (computedStatus.isEmpty()) {
                if (verbose) {
                    System.out.println("[DEBUG] No status found for extensions in: " + filename);
                }
                results.put(filename, InjectionResult.noStatusFound(extensions));
                return;
            }

            // Check for existing :extension-status: attribute
            Matcher statusMatcher = EXTENSION_STATUS_PATTERN.matcher(content);
            boolean hasExistingStatus = statusMatcher.find();

            if (hasExistingStatus) {
                String existingStatus = statusMatcher.group(1).trim().toLowerCase();
                String computedStatusId = computedStatus.get().getId();

                if (existingStatus.equals(computedStatusId)) {
                    if (verbose) {
                        System.out.println("[DEBUG] Status already correct in: " + filename + " (" + existingStatus + ")");
                    }
                    results.put(filename, InjectionResult.alreadyCorrect(existingStatus));
                    return;
                } else {
                    // Status mismatch - warn but update
                    System.out.println("[WARN] Status mismatch in " + filename +
                            ": declared=" + existingStatus + ", computed=" + computedStatusId);
                    results.put(filename, InjectionResult.mismatch(existingStatus, computedStatusId));

                    if (!dryRun) {
                        // Replace existing status with computed status
                        String updatedContent = statusMatcher.replaceFirst(
                                ":extension-status: " + computedStatusId);
                        Files.writeString(path, updatedContent);
                        System.out.println("[INFO] Updated status in: " + filename);
                    }
                    return;
                }
            }

            // No existing status - inject one
            String statusLine = ":extension-status: " + computedStatus.get().getId();

            // Find the best insertion point (after include::_attributes.adoc[])
            Matcher includeMatcher = INCLUDE_ATTRIBUTES_PATTERN.matcher(content);
            String updatedContent;

            if (includeMatcher.find()) {
                // Insert after the include::_attributes.adoc[] line
                int insertPos = includeMatcher.end();
                updatedContent = content.substring(0, insertPos) + "\n" + statusLine + content.substring(insertPos);
            } else {
                // Fallback: insert after the title line
                int titleEnd = content.indexOf("\n", content.indexOf("\n= "));
                if (titleEnd > 0) {
                    updatedContent = content.substring(0, titleEnd + 1) + statusLine + "\n" + content.substring(titleEnd + 1);
                } else {
                    System.err.println("[WARN] Could not find insertion point in: " + filename);
                    results.put(filename, InjectionResult.insertionFailed());
                    return;
                }
            }

            results.put(filename, InjectionResult.injected(computedStatus.get().getId(), extensions));

            if (!dryRun) {
                Files.writeString(path, updatedContent);
                System.out.println("[INFO] Injected status '" + computedStatus.get().getId() + "' in: " + filename);
            } else {
                System.out.println("[DRY-RUN] Would inject status '" + computedStatus.get().getId() + "' in: " + filename);
            }

        } catch (IOException e) {
            System.err.println("[ERROR] Failed to process file: " + path + " - " + e.getMessage());
            results.put(path.getFileName().toString(), InjectionResult.error(e.getMessage()));
        }
    }

    /**
     * Prints a summary of the injection results.
     */
    public void printSummary() {
        int injected = 0;
        int updated = 0;
        int correct = 0;
        int noExtensions = 0;
        int noStatus = 0;
        int errors = 0;

        for (InjectionResult result : results.values()) {
            switch (result.type) {
                case INJECTED:
                    injected++;
                    break;
                case MISMATCH:
                    updated++;
                    break;
                case ALREADY_CORRECT:
                    correct++;
                    break;
                case NO_EXTENSIONS:
                    noExtensions++;
                    break;
                case NO_STATUS_FOUND:
                    noStatus++;
                    break;
                case ERROR:
                case INSERTION_FAILED:
                    errors++;
                    break;
            }
        }

        System.out.println("\n=== Extension Status Injection Summary ===");
        System.out.println("  Injected new status:     " + injected);
        System.out.println("  Updated mismatched:      " + updated);
        System.out.println("  Already correct:         " + correct);
        System.out.println("  No :extensions: attr:    " + noExtensions);
        System.out.println("  Extensions without status: " + noStatus);
        System.out.println("  Errors:                  " + errors);
        System.out.println("  Total processed:         " + results.size());
    }

    /**
     * Returns the injection results for all processed files.
     */
    public Map<String, InjectionResult> getResults() {
        return results;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ExtensionStatusInjector <extensions-dir> <asciidoc-dir> [--dry-run] [--verbose]");
            System.err.println("  extensions-dir: Path to the Quarkus extensions directory");
            System.err.println("  asciidoc-dir:   Path to the AsciiDoc sources directory");
            System.err.println("  --dry-run:      Print what would be done without making changes");
            System.err.println("  --verbose:      Print detailed processing information");
            System.exit(1);
        }

        Path extensionsDir = Paths.get(args[0]);
        Path asciidocDir = Paths.get(args[1]);
        boolean dryRun = false;
        boolean verbose = false;

        for (int i = 2; i < args.length; i++) {
            if ("--dry-run".equals(args[i])) {
                dryRun = true;
            } else if ("--verbose".equals(args[i])) {
                verbose = true;
            }
        }

        System.out.println("[INFO] Extension Status Injector");
        System.out.println("[INFO] Extensions directory: " + extensionsDir);
        System.out.println("[INFO] AsciiDoc directory: " + asciidocDir);
        if (dryRun) {
            System.out.println("[INFO] Running in DRY-RUN mode - no files will be modified");
        }

        // Build the extension status resolver
        ExtensionStatusResolver resolver = new ExtensionStatusResolver()
                .scanExtensions(extensionsDir);

        if (verbose) {
            System.out.println("[DEBUG] Resolved extension statuses:");
            resolver.printAllStatuses();
        }

        // Process AsciiDoc files
        ExtensionStatusInjector injector = new ExtensionStatusInjector(resolver)
                .setDryRun(dryRun)
                .setVerbose(verbose)
                .processDirectory(asciidocDir);

        injector.printSummary();
    }

    /**
     * Result of processing a single file.
     */
    public static class InjectionResult {
        public enum Type {
            INJECTED,
            MISMATCH,
            ALREADY_CORRECT,
            NO_EXTENSIONS,
            NO_STATUS_FOUND,
            INSERTION_FAILED,
            ERROR
        }

        public final Type type;
        public final String details;
        public final String extensions;

        private InjectionResult(Type type, String details, String extensions) {
            this.type = type;
            this.details = details;
            this.extensions = extensions;
        }

        public static InjectionResult injected(String status, String extensions) {
            return new InjectionResult(Type.INJECTED, status, extensions);
        }

        public static InjectionResult mismatch(String existing, String computed) {
            return new InjectionResult(Type.MISMATCH, existing + " -> " + computed, null);
        }

        public static InjectionResult alreadyCorrect(String status) {
            return new InjectionResult(Type.ALREADY_CORRECT, status, null);
        }

        public static InjectionResult noExtensions() {
            return new InjectionResult(Type.NO_EXTENSIONS, null, null);
        }

        public static InjectionResult noStatusFound(String extensions) {
            return new InjectionResult(Type.NO_STATUS_FOUND, null, extensions);
        }

        public static InjectionResult insertionFailed() {
            return new InjectionResult(Type.INSERTION_FAILED, null, null);
        }

        public static InjectionResult error(String message) {
            return new InjectionResult(Type.ERROR, message, null);
        }
    }
}
