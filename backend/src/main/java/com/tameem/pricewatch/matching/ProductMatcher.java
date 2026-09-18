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
 * <p>
 * "Agreeing" is not string equality. A different retailer writes the same model and brand
 * differently — {@code 'Q20i'} against {@code 'Soundcore Q20i'}, {@code 'Anker'} against
 * {@code 'soundcore by Anker'} — so {@link #sameValue} compares each field on its own terms
 * (see {@link #sameModel} and {@link #sameBrand}). Capacity is the exception and stays exact:
 * a quantity difference is always a real one. The reconstructed precision measurement for
 * this change is in {@code MatcherEvalTest} — 88.9% to 91.7%, recall 72.7% to 100%, on frozen
 * real extractions.
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
            if (va != null && vb != null && !sameValue(field, va, vb)) {
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
            if (va != null && vb != null && sameValue(field, va, vb)) {
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
     * Model tier words: an extra one of these on one side marks a different variant, so it
     * blocks a match even when the distinctive model code is shared — a Q20i and a "Q20i
     * Pro" are not the same product. Kept small and specific; a descriptor that is not here
     * (a brand name leaking in, a redundant "Mark 5") is treated as noise, not a difference.
     */
    private static final Set<String> MODEL_TIER_WORDS = Set.of(
            "pro", "max", "plus", "ultra", "mini", "lite", "air", "se", "xl", "xs", "ti", "gen", "fe");

    /**
     * Whether two stated values for one hard field mean the same product.
     * <p>
     * Dispatched by field because the fields are not alike. capacity is a quantity, where a
     * difference is always a real difference; model and brand are names a different retailer
     * writes differently for the identical product, where an exact-string test threw away
     * genuine matches — {@code 'Q20i'} against {@code 'Soundcore Q20i'}, {@code 'soundcore'}
     * against {@code 'Soundcore by Anker'}.
     * <p>
     * Comparison only: callers keep the original strings for the rejection message, so it
     * still shows what each retailer actually wrote.
     */
    private static boolean sameValue(String field, String a, String b) {
        return switch (field) {
            case "model" -> sameModel(a, b);
            case "brand" -> sameBrand(a, b);
            // capacity and anything else: a quantity, compared exactly bar case and spacing.
            // Retailers write "2TB" and "2 TB"; they do not write "2TB" to mean "4TB".
            default -> normalized(a).equals(normalized(b));
        };
    }

    /**
     * Two model strings name the same product when their distinctive alphanumeric codes
     * agree and nothing tier-distinguishing separates them.
     * <p>
     * The distinctive code is the token carrying both letters and digits — "Q20i",
     * "WH-1000XM5", "9800X3D". That token is the model's identity; the words around it are
     * mostly the brand or filler a given retailer chose to fold in. So:
     * <ul>
     *   <li>codes must match exactly — "Q20i" ≠ "Q21i", "9800X3D" ≠ "9850X3D" — and a code
     *       stated on one side but not the other is a difference too.</li>
     *   <li>with the codes equal, extra descriptive words are ignored, which is what lets
     *       "Q20i" match "Soundcore Q20i" and "WH-1000XM5" match "WH-1000XM5 Mark 5" — unless
     *       the extra word is a {@link #MODEL_TIER_WORDS tier word}, since "AK820" and "AK820
     *       Pro" really are different.</li>
     *   <li>with no code on either side, there is no anchor to trust, so anything short of an
     *       exact match stays a difference. This is what keeps "iPhone 17 Pro" apart from
     *       "iPhone 17 Pro Max" and "Space One" from "Space One Pro" — a subset in tokens,
     *       but a different product, and nothing alphanumeric to lean on.</li>
     * </ul>
     * The bias throughout is to reject when unsure: a missed match costs a manual re-add, a
     * wrong match corrupts a price history.
     */
    private static boolean sameModel(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (multisetEquals(ta, tb)) {
            return true;
        }
        Set<String> codesA = codeTokens(ta);
        Set<String> codesB = codeTokens(tb);
        if (!codesA.equals(codesB) || codesA.isEmpty()) {
            return false;
        }
        // Codes agree and are non-empty. Accept unless an extra word marks a variant.
        Set<String> extras = symmetricDifference(ta, tb);
        return extras.stream().noneMatch(MODEL_TIER_WORDS::contains);
    }

    /**
     * Two brand strings name the same maker when one side's words are contained in the
     * other's — "Anker" and "soundcore by Anker", "Samsung" and "Samsung Electronics" are
     * the same brand written at different lengths. A brand has no tier the way a model does,
     * so containment is safe here where it is not for model.
     */
    private static boolean sameBrand(String a, String b) {
        Set<String> sa = new HashSet<>(tokens(a));
        Set<String> sb = new HashSet<>(tokens(b));
        if (sa.isEmpty() || sb.isEmpty()) {
            return normalized(a).equals(normalized(b));
        }
        return sa.containsAll(sb) || sb.containsAll(sa);
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Lowercased alphanumeric tokens, splitting on everything else so "WH-1000XM5" -> wh, 1000xm5. */
    private static List<String> tokens(String value) {
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> !t.isBlank())
                .toList();
    }

    /** Tokens carrying both a letter and a digit — the distinctive model codes. */
    private static Set<String> codeTokens(List<String> tokens) {
        Set<String> codes = new HashSet<>();
        for (String token : tokens) {
            boolean hasLetter = token.chars().anyMatch(Character::isLetter);
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            if (hasLetter && hasDigit) {
                codes.add(token);
            }
        }
        return codes;
    }

    private static boolean multisetEquals(List<String> a, List<String> b) {
        List<String> sa = new ArrayList<>(a);
        List<String> sb = new ArrayList<>(b);
        sa.sort(null);
        sb.sort(null);
        return sa.equals(sb);
    }

    private static Set<String> symmetricDifference(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        Set<String> diff = new HashSet<>(sa);
        diff.addAll(sb);
        Set<String> shared = new HashSet<>(sa);
        shared.retainAll(sb);
        diff.removeAll(shared);
        return diff;
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
