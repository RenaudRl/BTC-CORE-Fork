package dev.btc.core.integrity;

import dev.btc.core.BTCCoreBuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
