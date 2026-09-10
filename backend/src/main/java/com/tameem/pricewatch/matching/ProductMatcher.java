package com.tameem.pricewatch.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether two product titles describe the same product.
 * <p>
 * The load-bearing rule is a hard mismatch gate on capacity and model: if both titles
 * state one of those and they disagree, the pair is rejected outright, with no scoring and
 * no averaging. Similarity alone cannot carry this — measured on real near-miss pairs,
 * embedding and cross-encoder similarity produced false positives at 76.7% and 46.7%
 * respectively, because "1TB SSD" and "4TB SSD" read as almost the same sentence.
 * <p>
 * A field present on one side and absent on the other is neutral: titles are written by
 * different retailers and simply mention different things. Only a genuine
 * present-on-both-sides disagreement rejects.
 * <p>
 * Neutral is not enough to attach on, though. Measured against the existing catalogue,
 * treating "nothing conflicts" as a match ran at 53% precision: every remaining error was a
 * pair where neither title yielded a comparable field, so a tablet stand and a desk mount
 * disagreed about nothing and were matched for it. A match now needs at least one hard
 * field stated on both sides and agreeing; anything less is UNCERTAIN, which never
 * attaches.
 */
@Component
public class ProductMatcher {

    private static final Logger log = LoggerFactory.getLogger(ProductMatcher.class);

    /**
     * Fields where a stated disagreement means a different product, full stop.
     * <p>
     * capacity is a clean numeric-plus-unit spec: two titles either state the same quantity
     * or describe different products.
     * <p>
     * model was briefly demoted to advisory because the previous extractor phrased it
     * inconsistently — the same B&amp;H pair came back "990 PRO" on one run and
     * "990 PRO PCIe 4.0 x4 M.2" on the next, so a disagreement meant "worded differently"
     * as often as "different product". That was a property of the model, not of the field:
     * the current extractor is deterministic across repeated runs, so the field is
     * trustworthy again. It is what separates a Ryzen 9800X3D from a 9850X3D.
     * <p>
     * brand was never gated at all, which let a Chromebook match a trackball and a sneaker
     * match compression gloves — both were extracted with brands, they simply were never
     * compared.
     * <p>
     * "size" is still deliberately absent: nominally a unit spec, but the extractor puts
     * interface strings in it, e.g. "PCIe 4.0 x16" for an SSD.
     */
    private static final String[] HARD_FIELDS = {"capacity", "model", "brand"};

    /**
     * Only used when NEITHER title yielded any attribute at all — generic names with no
     * spec, size or colour. Deliberately high: below this the pair is uncertain, not a
     * match, because a weak word overlap is exactly the signal that proved unreliable.
     */
    private static final double FALLBACK_SIMILARITY_THRESHOLD = 0.8;

    public enum Decision { MATCH, REJECT, UNCERTAIN }

    /** The verdict plus a human-readable reason, so a skipped candidate can be explained. */
    public record Outcome(Decision decision, String reason) {
        public boolean isMatch() {
            return decision == Decision.MATCH;
        }
    }

    /**
     * @param left       attributes of the product being tracked, empty when extraction failed
     * @param right      attributes of the candidate, empty when extraction failed
     * @param leftTitle  raw title, used only for the no-attributes fallback
     * @param rightTitle raw title, used only for the no-attributes fallback
     */
    public Outcome match(Optional<ProductAttributes> left, Optional<ProductAttributes> right,
                         String leftTitle, String rightTitle) {

        if (left.isEmpty() || right.isEmpty()) {
            // "We could not tell" is not "they match" — never auto-attach on a failed read.
            return new Outcome(Decision.UNCERTAIN, "attribute extraction unavailable");
        }

        ProductAttributes a = left.get();
        ProductAttributes b = right.get();

        for (String field : HARD_FIELDS) {
            String va = valueOf(a, field);
            String vb = valueOf(b, field);
            if (va != null && vb != null && !sameValue(va, vb)) {
                return new Outcome(Decision.REJECT,
                        "%s differs: '%s' vs '%s'".formatted(field, va, vb));
            }
        }

        // Absence of conflict is not evidence of sameness. Two products that state nothing
        // comparable — a tablet stand and a desk mount, a casserole dish and a slow cooker —
        // conflict on nothing at all, and were being matched on that basis. At least one
        // hard field has to actually agree.
        List<String> agreed = new ArrayList<>();
        for (String field : HARD_FIELDS) {
            String va = valueOf(a, field);
            String vb = valueOf(b, field);
            if (va != null && vb != null && sameValue(va, vb)) {
                agreed.add(field);
            }
        }
        if (!agreed.isEmpty()) {
            return new Outcome(Decision.MATCH, "agrees on " + String.join(", ", agreed));
        }

        if (!a.isEmpty() || !b.isEmpty()) {
            // Something was extracted, but nothing the two titles both state. Not a
            // rejection — there is no disagreement — but not enough to attach on.
            return new Outcome(Decision.UNCERTAIN,
                    "no hard attribute stated on both sides to compare");
        }

        // Neither title stated anything extractable — the rare generic-name case.
        double similarity = titleSimilarity(leftTitle, rightTitle);
        if (similarity >= FALLBACK_SIMILARITY_THRESHOLD) {
            return new Outcome(Decision.MATCH,
                    "no attributes on either title; titles %.2f similar".formatted(similarity));
        }
        return new Outcome(Decision.UNCERTAIN,
                "no attributes on either title; titles only %.2f similar".formatted(similarity));
    }

    /**
     * Whether two stated values mean the same thing, ignoring case and all whitespace.
     * <p>
     * Retailers write the same quantity differently — B&H lists "2TB" where Currys lists
     * "2 TB" — and with capacity the only hard field, treating those as a disagreement
     * silently threw away correct matches. Cross-store formatting differences are the
     * normal case for this feature, not an edge one.
     * <p>
     * Comparison only: the original strings are what a rejection message reports, so it
     * still shows what each retailer actually wrote.
     */
    private static boolean sameValue(String a, String b) {
        return a.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .equals(b.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
    }

    private static String valueOf(ProductAttributes attributes, String field) {
        String value = switch (field) {
            case "capacity" -> attributes.capacity();
            case "model" -> attributes.model();
            case "brand" -> attributes.brand();
            case "size" -> attributes.size();
            default -> null;
        };
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Jaccard overlap of lowercased word tokens. */
    private static double titleSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String title) {
        return Arrays.stream(title.toLowerCase().split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }
}
