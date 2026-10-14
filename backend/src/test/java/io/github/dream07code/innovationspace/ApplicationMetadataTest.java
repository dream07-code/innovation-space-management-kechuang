package io.github.dream07code.innovationspace;

import java.io.DataInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationMetadataTest {
    @Test
    void applicationHasTheApprovedRootPackageAndBootstrapAnnotation() {
        assertEquals("io.github.dream07code.innovationspace", InnovationSpaceApplication.class.getPackageName());
        assertTrue(InnovationSpaceApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

    @Test
    void applicationIsCompiledForJava21() throws IOException {
        var resource = InnovationSpaceApplication.class.getResourceAsStream("InnovationSpaceApplication.class");
        assertNotNull(resource);
        try (var input = new DataInputStream(resource)) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            assertEquals(65, input.readUnsignedShort());
        }
    }
}
