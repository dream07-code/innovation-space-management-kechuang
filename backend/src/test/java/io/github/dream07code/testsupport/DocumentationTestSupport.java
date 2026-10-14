package io.github.dream07code.testsupport;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import static org.junit.jupiter.api.Assertions.*;

public final class DocumentationTestSupport {
    private DocumentationTestSupport() { }

    public static Set<String> resources(String html) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private void collect(HTML.Tag tag, MutableAttributeSet attrs) {
                Object link = tag == HTML.Tag.SCRIPT ? attrs.getAttribute(HTML.Attribute.SRC)
                        : tag == HTML.Tag.LINK ? attrs.getAttribute(HTML.Attribute.HREF) : null;
                if (link != null && link.toString().startsWith("webjars/")) paths.add("/" + link);
            }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) { collect(tag, attrs); }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) { collect(tag, attrs); }
        }, true);
        assertTrue(paths.stream().anyMatch(path -> path.endsWith(".js")));
        assertTrue(paths.stream().anyMatch(path -> path.endsWith(".css")));
        return paths;
    }

    public static Set<String> bundledResources() throws Exception {
        try (var stream = DocumentationTestSupport.class.getResourceAsStream("/META-INF/resources/doc.html")) {
            assertNotNull(stream);
            return resources(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static void assertDocumentationDisabled(int port) throws Exception {
        var paths = new LinkedHashSet<>(bundledResources());
        paths.addAll(java.util.List.of("/doc.html", "/v3/api-docs", "/v3/api-docs/swagger-config",
                "/v3/api-docs/innovation-space", "/swagger-ui.html", "/swagger-ui/index.html"));
        for (String path : paths) assertEquals(404, HttpTestSupport.get(port, path).statusCode(), path);
        assertEquals("UP", HttpTestSupport.json(HttpTestSupport.get(port, "/actuator/health")).path("status").asText());
    }
}
