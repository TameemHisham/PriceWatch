package com.tameem.pricewatch.matching;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
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
 */
@Component
public class ProductMatcher {

    /** Fields where a stated disagreement means a different product, full stop. */
    private static final String[] HARD_FIELDS = {"capacity", "model"};

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
            if (va != null && vb != null && !va.equalsIgnoreCase(vb)) {
                return new Outcome(Decision.REJECT,
                        "%s differs: '%s' vs '%s'".formatted(field, va, vb));
            }
        }

        if (!a.isEmpty() || !b.isEmpty()) {
            return new Outcome(Decision.MATCH, "no conflicting attributes");
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

    private static String valueOf(ProductAttributes attributes, String field) {
        String value = switch (field) {
            case "capacity" -> attributes.capacity();
            case "model" -> attributes.model();
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
