package com.tameem.pricewatch.matching;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-time, live: extracts attributes for every unique title in eval-pairs.jsonl using the
 * real {@link AttributeExtractor} against Groq, and freezes them to
 * src/test/resources/matching/extractions.json.
 * <p>
 * Frozen so the actual evaluation ({@link MatcherEvalTest}) is deterministic and offline —
 * the extractor is temperature 0, so the freeze is faithful, and the before/after matcher
 * comparison then runs on identical inputs and measures only the matcher. Tagged {@code
 * live} so it never runs in the normal suite. Re-run with:
 * <pre>./mvnw -o test -Dtest=MatcherEvalPrep -Dgroups=live</pre>
 */
@Tag("live")
class MatcherEvalPrep {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void freezeExtractions() throws Exception {
        String key = loadGroqKey();
        assertFalse(key == null || key.isBlank(), "no groq.api.key on the test classpath");
        AttributeExtractor extractor = new AttributeExtractor(key);
        assertTrue(extractor.isConfigured());

        Set<String> titles = uniqueTitles();
        System.out.println("Extracting " + titles.size() + " unique titles from Groq...");

        ObjectNode out = MAPPER.createObjectNode();
        int i = 0;
        for (String title : titles) {
            Optional<ProductAttributes> attrs = extractWithRetry(extractor, title);
            assertTrue(attrs.isPresent(), "extraction failed (rate limit?) for: " + title);
            ProductAttributes a = attrs.get();
            ObjectNode node = out.putObject(title);
            put(node, "brand", a.brand());
            put(node, "model", a.model());
            put(node, "capacity", a.capacity());
            put(node, "size", a.size());
            put(node, "color", a.color());
            System.out.printf("[%d/%d] %s -> brand=%s model=%s capacity=%s%n",
                    ++i, titles.size(), title.substring(0, Math.min(45, title.length())),
                    a.brand(), a.model(), a.capacity());
            Thread.sleep(2200); // stay under Groq's 30 req/min
        }

        Path target = Path.of("src/test/resources/matching/extractions.json");
        Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("Wrote " + target.toAbsolutePath());
    }

    /** New extractor per attempt would lose the cache; the extractor already caches per title. */
    private static Optional<ProductAttributes> extractWithRetry(AttributeExtractor extractor, String title)
            throws InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            Optional<ProductAttributes> attrs = extractor.extract(title);
            if (attrs.isPresent()) {
                return attrs;
            }
            System.out.println("  retry after empty extraction (attempt " + (attempt + 1) + ")");
            Thread.sleep(20_000); // a minute's worth of RPM headroom
        }
        return Optional.empty();
    }

    static Set<String> uniqueTitles() throws Exception {
        Set<String> titles = new LinkedHashSet<>();
        for (JsonNode pair : readPairs()) {
            titles.add(pair.get("a").asString());
            titles.add(pair.get("b").asString());
        }
        return titles;
    }

    static ArrayNode readPairs() throws Exception {
        ArrayNode pairs = MAPPER.createArrayNode();
        try (InputStream in = MatcherEvalPrep.class.getResourceAsStream("/matching/eval-pairs.jsonl")) {
            assertTrue(in != null, "missing eval-pairs.jsonl");
            List<String> lines = new ArrayList<>(
                    List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
            for (String line : lines) {
                if (!line.isBlank()) {
                    pairs.add(MAPPER.readTree(line));
                }
            }
        }
        return pairs;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String loadGroqKey() throws Exception {
        Properties props = new Properties();
        try (InputStream in = MatcherEvalPrep.class.getResourceAsStream("/application-secrets.properties")) {
            if (in == null) return System.getenv("GROQ_API_KEY");
            props.load(in);
        }
        return props.getProperty("groq.api.key");
    }
}
