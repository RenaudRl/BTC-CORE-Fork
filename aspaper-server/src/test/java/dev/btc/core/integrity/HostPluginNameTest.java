package dev.btc.core.integrity;

import dev.btc.core.BTCCoreBuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The host lookup constant must stay equal to the name Paper registers the plugin under. */
class HostPluginNameTest {

    @Test
    @DisplayName("HOST_PLUGIN equals paper { name } in plugin/build.gradle.kts")
    void constantMatchesPluginDescriptor() throws IOException {
        Path script = findPluginScript();
        assumeTrue(script != null, "plugin build script not reachable from this working directory");

        Matcher declared = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(script));
        assertTrue(declared.find(), "no `name = \"...\"` found in the paper { } block");
        assertEquals(declared.group(1), BTCCoreBuild.HOST_PLUGIN);
    }

    @Test
    @DisplayName("no Java source still looks the host plugin up by the old literal name")
    void noSourceHardcodesTheOldPluginName() throws IOException {
        Path script = findPluginScript();
        assumeTrue(script != null, "repository root not reachable from this working directory");

        // Split so that this very file does not contain the literal it forbids.
        String forbidden = "\"AS" + "Paper\"";
        Path root = script.getParent().getParent();
        // The Gradle root name lives in settings.gradle.kts, which is not a Java source and is not scanned.
        java.util.Set<String> skipped = java.util.Set.of(".git", ".gradle", "build", "paper-server", "paper-api");
        List<String> offenders = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped.contains(String.valueOf(dir.getFileName())) ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    // Decoded leniently: a stray byte in some source must not abort the scan.
                    String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        String code = line.strip();
                        // A comment may still tell the history; only code can break the lookup.
                        if (code.contains(forbidden) && !code.startsWith("*") && !code.startsWith("//")
                            && !code.startsWith("/*")) {
                            offenders.add(root.relativize(file).toString());
                            break;
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertTrue(offenders.isEmpty(), "use BTCCoreBuild.HOST_PLUGIN instead of the old name in: " + offenders);
    }

    /** The test working directory is a module folder or a run folder below it: walk up to the root. */
    private static Path findPluginScript() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("plugin").resolve("build.gradle.kts");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
