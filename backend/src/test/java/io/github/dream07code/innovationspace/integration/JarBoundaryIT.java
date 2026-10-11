package io.github.dream07code.innovationspace.integration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JarBoundaryIT {
    @Test void executableArtifactIncludesOnlyProductionClassesAndSafeResources() throws Exception {
        try (var jar = new JarFile(Path.of("target/innovation-space-backend-0.1.0-SNAPSHOT.jar").toFile())) {
            assertEquals("io.github.dream07code.innovationspace.InnovationSpaceApplication",
                    jar.getManifest().getMainAttributes().getValue("Start-Class"));
            assertNotNull(jar.getJarEntry("BOOT-INF/classes/io/github/dream07code/innovationspace/InnovationSpaceApplication.class"));
            var entries = jar.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(name -> name.startsWith("BOOT-INF/classes/")).toList();
            for (String name : entries) {
                String lower = name.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("testsupport") || lower.contains("probe")
                        || lower.contains("browsersmoke") || lower.contains(".env")
                        || (lower.endsWith(".sql") && !lower.matches("boot-inf/classes/db/migration/v[1-5]__.*\\.sql")), name);
            }
        }
    }
}
