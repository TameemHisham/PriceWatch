package com.tameem.pricewatch.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
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
 */
@Component
public class ProductMatcher {

    private static final Logger log = LoggerFactory.getLogger(ProductMatcher.class);

    /**
     * Fields where a stated disagreement means a different product, full stop.
     * <p>
     * Only capacity. It is a clean numeric-plus-unit spec, so two titles either state the
     * same quantity or genuinely describe different products.
     * <p>
     * "model" used to sit here and was demoted: the extractor phrases it inconsistently
     * across near-identical titles — the same B&amp;H pair extracted as "990 PRO" on one run
     * and "990 PRO PCIe 4.0 x4 M.2" on the next — so a disagreement was as likely to mean
     * "worded differently this time" as "different product". "size" is deliberately not
     * here either: it is nominally a unit spec but the extractor puts interface strings in
     * it, e.g. "PCIe 4.0 x16" for an SSD.
     */
    private static final String[] HARD_FIELDS = {"capacity"};

    /** Disagreements worth recording but never worth rejecting on. */
    private static final String[] ADVISORY_FIELDS = {"model"};

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

        // Advisory only: recorded so a surprising match can be explained later, but never
        // enough on its own to throw a candidate away.
        String advisory = "";
        for (String field : ADVISORY_FIELDS) {
            String va = valueOf(a, field);
            String vb = valueOf(b, field);
            if (va != null && vb != null && !sameValue(va, vb)) {
                advisory = " (note: %s differs, '%s' vs '%s' — advisory only)"
                        .formatted(field, va, vb);
                log.info("Matched despite {} difference: '{}' vs '{}'", field, va, vb);
            }
        }

        if (!a.isEmpty() || !b.isEmpty()) {
            return new Outcome(Decision.MATCH, "no conflicting hard attributes" + advisory);
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
