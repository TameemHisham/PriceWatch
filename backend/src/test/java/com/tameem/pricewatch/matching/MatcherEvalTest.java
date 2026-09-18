package com.tameem.pricewatch.matching;

import com.tameem.pricewatch.matching.ProductMatcher.Decision;
import com.tameem.pricewatch.matching.ProductMatcher.Outcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Precision evaluation for {@link ProductMatcher}, run on frozen real extractions.
 * <p>
 * The project's original matcher evaluation was a {@link
 * com.tameem.pricewatch.maintenance.DiscoveryBackfill} dry run against the live catalogue
 * with correct/wrong counted by hand (commit 6893c4a: 53% before positive-agreement, 82%
 * after). That is not reproducible on demand — it depends on the live extractor, live
 * store search, and manual labelling, and the stores have changed since. This reconstructs
 * the part a matcher change actually affects: real title pairs drawn from production
 * discovery logs and the catalogue, their attributes extracted once by the real Groq
 * extractor and frozen (see {@link MatcherEvalPrep}), and the matcher run over them
 * offline. Freezing the extractions is what lets a before/after comparison run on identical
 * inputs and measure only the matcher.
 * <p>
 * Two known-unreachable pairs are labelled and excluded from the pass/fail invariants but
 * kept in the printed precision: the matcher does not gate colour (ZV-1F white vs black)
 * or reject on brand-only agreement (Tower casserole vs slow cooker), both documented in
 * 6893c4a as out of scope for the attribute gate. They are constant across before/after.
 */
class MatcherEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProductMatcher matcher = new ProductMatcher();

    private record Pair(boolean same, String src, String note, String a, String b) {}

    @Test
    void reportsPrecisionAndHoldsRequiredInvariants() throws Exception {
        Map<String, ProductAttributes> extractions = loadExtractions();
        List<Pair> pairs = loadPairs();

        int tp = 0, fp = 0, fn = 0, tn = 0;
        List<String> falsePositives = new ArrayList<>();
        List<String> falseNegatives = new ArrayList<>();

        for (Pair pair : pairs) {
            Outcome outcome = matcher.match(
                    attrs(extractions, pair.a()), attrs(extractions, pair.b()),
                    pair.a(), pair.b());
            boolean matched = outcome.decision() == Decision.MATCH;

            if (pair.same() && matched) tp++;
            else if (!pair.same() && matched) { fp++; falsePositives.add(describe(pair, outcome)); }
            else if (pair.same() && !matched) { fn++; falseNegatives.add(describe(pair, outcome)); }
            else tn++;
        }

        int predictedPositive = tp + fp;
        double precision = predictedPositive == 0 ? 1.0 : (double) tp / predictedPositive;
        int actualPositive = tp + fn;
        double recall = actualPositive == 0 ? 1.0 : (double) tp / actualPositive;

        System.out.println("\n===== ProductMatcher precision evaluation (" + pairs.size() + " real pairs) =====");
        System.out.printf("MATCH decisions: %d | correct (TP): %d | wrong (FP): %d%n", predictedPositive, tp, fp);
        System.out.printf("precision = %.1f%%   (TP / MATCH)%n", precision * 100);
        System.out.printf("recall    = %.1f%%   (TP / same-product pairs)%n", recall * 100);
        System.out.println("false positives:");
        falsePositives.forEach(s -> System.out.println("   FP " + s));
        System.out.println("false negatives:");
        falseNegatives.forEach(s -> System.out.println("   FN " + s));
        System.out.println("=========================================================\n");

        // --- Required invariants (independent of the headline precision number) ---

        // The three reported bugs must now match.
        assertMatch(extractions, "Sony WH-1000XM5 Noise Cancelling Wireless Headphones, Hi-Res Audio, Best Phone Call Quality, 30 Hours Battery Life, Wearing Detection, Alexa Voice Assistant, Black, UAE Version - 1-Year warranty",
                "Sony WH-1000XM5 Mark 5 Over-Ear Headphones, Active Noise Cancelling, Bluetooth, USB (Charging), Built-in Microphone, Black");
        assertMatch(extractions, "soundcore by Anker Q20i Hybrid ANC Foldable Headphones, 40H",
                "Anker Soundcore Q20i On-Ear Headphones, Active Noise Cancelling, Bluetooth, USB (Charging), Built-in Microphone, Black");
        assertMatch(extractions, "soundcore by Anker Q20i Hybrid ANC Foldable Headphones, 40H",
                "Soundcore by Anker Q20i Wireless Hybrid Noise Canceling Over-Ear Headphones (Black)");

        // The guards that must never loosen.
        assertReject(extractions, "soundcore by Anker Q20i Hybrid ANC Foldable Headphones, 40H",
                "SOUNDCORE Q21i Wireless Bluetooth Noise-Cancelling Headphones - Black");          // Q20i vs Q21i
        assertReject(extractions, "Samsung 1TB 990 PRO PCIe 4.0 x4 M.2 Internal SSD",
                "HighPoint 4TB RocketAIC 7505HM PCIe 4.0 x16 NVMe SSD (4 x 1TB)");                 // 1TB vs 4TB
        assertReject(extractions, "Apple iPhone 17 Pro Max 512 GB, A19 Pro Chip, Cosmic Orange",
                "Apple iPhone 17 Pro 512 GB, A19 Pro Chip, Cosmic Orange");                        // Pro Max vs Pro (subset trap)
        assertReject(extractions, "AMD Ryzen 7 9800X3D Processor", "AMD Ryzen 7 9850X3D Processor");
        assertReject(extractions, "SOUNDCORE Space One Wireless Bluetooth Noise-Cancelling Headphones - Cream",
                "SOUNDCORE Space One Pro Wireless Bluetooth Noise-Cancelling Headphones - Cream White"); // One vs One Pro

        // Precision must not regress below the positive-agreement baseline the project set.
        assertTrue(precision >= 0.82,
                "precision regressed below the 82% baseline: " + precision);
    }

    private void assertMatch(Map<String, ProductAttributes> ext, String a, String b) {
        Outcome o = matcher.match(attrs(ext, a), attrs(ext, b), a, b);
        assertEquals(Decision.MATCH, o.decision(),
                "expected MATCH but got " + o.decision() + " (" + o.reason() + ") for:\n  " + a + "\n  " + b);
    }

    private void assertReject(Map<String, ProductAttributes> ext, String a, String b) {
        Outcome o = matcher.match(attrs(ext, a), attrs(ext, b), a, b);
        assertEquals(Decision.REJECT, o.decision(),
                "expected REJECT but got " + o.decision() + " (" + o.reason() + ") for:\n  " + a + "\n  " + b);
    }

    private static Optional<ProductAttributes> attrs(Map<String, ProductAttributes> ext, String title) {
        ProductAttributes a = ext.get(title);
        assertTrue(a != null, "no frozen extraction for: " + title);
        return Optional.of(a);
    }

    private static String describe(Pair pair, Outcome outcome) {
        return "[" + pair.src() + "] " + pair.note() + " -> " + outcome.decision()
                + " (" + outcome.reason() + ")";
    }

    private static Map<String, ProductAttributes> loadExtractions() throws Exception {
        try (InputStream in = MatcherEvalTest.class.getResourceAsStream("/matching/extractions.json")) {
            assertTrue(in != null, "missing extractions.json — run MatcherEvalPrep with -Dgroups=live first");
            JsonNode root = MAPPER.readTree(in);
            Map<String, ProductAttributes> out = new LinkedHashMap<>();
            root.propertyNames().forEach(title -> {
                JsonNode n = root.get(title);
                out.put(title, new ProductAttributes(
                        text(n, "brand"), text(n, "model"), text(n, "capacity"),
                        text(n, "size"), text(n, "color")));
            });
            return out;
        }
    }

    private static List<Pair> loadPairs() throws Exception {
        try (InputStream in = MatcherEvalTest.class.getResourceAsStream("/matching/eval-pairs.jsonl")) {
            assertTrue(in != null, "missing eval-pairs.jsonl");
            List<Pair> pairs = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonNode p = MAPPER.readTree(line);
                pairs.add(new Pair(p.get("same").asBoolean(), p.get("src").asString(),
                        p.get("note").asString(), p.get("a").asString(), p.get("b").asString()));
            }
            return pairs;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }
}
