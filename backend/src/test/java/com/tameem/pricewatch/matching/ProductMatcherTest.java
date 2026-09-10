package com.tameem.pricewatch.matching;

import com.tameem.pricewatch.matching.ProductMatcher.Decision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProductMatcherTest {

    private final ProductMatcher matcher = new ProductMatcher();

    private static Optional<ProductAttributes> attrs(String brand, String model, String capacity) {
        return Optional.of(new ProductAttributes(brand, model, capacity, null, null));
    }

    /** The case the gate exists for: a real B&H search hit for a 1TB query. */
    @Test
    void rejectsOnCapacityDisagreement() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Samsung", "990 PRO", "1TB"),
                attrs("HighPoint", "RocketAIC 7505HM", "4TB"),
                "Samsung 1TB 990 PRO PCIe 4.0 x4 M.2 Internal SSD",
                "HighPoint 4TB RocketAIC 7505HM PCIe 4.0 x16 NVMe SSD (4 x 1TB)");
        assertEquals(Decision.REJECT, outcome.decision());
        assertTrue(outcome.reason().contains("capacity"), outcome.reason());
    }

    /** model gates again: it is what separates a Ryzen 9800X3D from a 9850X3D. */
    @Test
    void rejectsOnModelDisagreement() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Apple", "iPhone 17 Pro Max", "512 GB"),
                attrs("Apple", "iPhone 17 Pro", "512 GB"),
                "Apple iPhone 17 Pro Max 512 GB", "Apple iPhone 17 Pro 512 GB");
        assertEquals(Decision.REJECT, outcome.decision());
        assertTrue(outcome.reason().contains("model"), outcome.reason());
    }

    /** The real backfill false positive: same brand and no stated capacity either side. */
    @Test
    void rejectsTwoDifferentProcessorsOfTheSameBrand() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("AMD", "Ryzen 7 9800X3D", null),
                attrs("AMD", "Ryzen 7 9850X3D", null),
                "AMD Ryzen 7 9800X3D", "AMD Ryzen 7 9850X3D Processor");
        assertEquals(Decision.REJECT, outcome.decision());
    }

    /** brand gates too: a Chromebook and a trackball both had brands, never compared. */
    @Test
    void rejectsOnBrandDisagreement() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("LENOVO", null, null),
                attrs("Kensington", null, null),
                "LENOVO IdeaPad Slim 3 14\" Chromebook",
                "Kensington SlimBlade Pro EQ Wireless Trackball");
        assertEquals(Decision.REJECT, outcome.decision());
        assertTrue(outcome.reason().contains("brand"), outcome.reason());
    }

    /**
     * The heatsink pair still matches, because the current extractor returns "990 PRO" for
     * both titles. This is the pair that broke when model last gated, so it is pinned: if
     * extraction ever becomes non-deterministic again, this is where it shows up.
     */
    @Test
    void matchesTheHeatsinkPairWhichExtractsTheSameModelOnBothSides() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Samsung", "990 PRO", "1TB"),
                attrs("Samsung", "990 PRO", "1TB"),
                "Samsung 1TB 990 PRO PCIe 4.0 x4 M.2 Internal SSD",
                "Samsung 1TB 990 PRO PCIe 4.0 x4 M.2 Internal SSD with Heatsink");
        assertEquals(Decision.MATCH, outcome.decision());
    }

    /**
     * The exact pair that regressed in live verification: B&H states "2TB" where the query
     * title said "2 TB", and the gate rejected a genuinely identical product. Retailers
     * format quantities differently, which is the normal case for cross-store matching.
     */
    @Test
    void treatsCapacityAsEqualAcrossRetailerSpacing() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Samsung", "T7", "2 TB"),
                attrs("Samsung", "T7", "2TB"),
                "SAMSUNG T7 Portable External SSD - 2 TB, Grey",
                "Samsung 2TB T7 Portable SSD (Titan Gray)");
        assertEquals(Decision.MATCH, outcome.decision(), outcome.reason());
    }

    /** Spacing must not mask a real difference either. */
    @Test
    void stillRejectsWhenSpacingDiffersAndSoDoesTheQuantity() {
        assertEquals(Decision.REJECT, matcher.match(
                attrs("Samsung", "T7", "1TB"),
                attrs("Samsung", "T7", "2 TB"),
                "a", "b").decision());
    }

    /** A rejection still reports what each retailer actually wrote, not the normalised form. */
    @Test
    void rejectionMessageKeepsTheOriginalFormatting() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Samsung", "T7", "1TB"),
                attrs("Samsung", "T7", "2 TB"),
                "a", "b");
        assertTrue(outcome.reason().contains("'2 TB'"), outcome.reason());
    }

    /** Capacity still rejects even when everything else agrees. */
    @Test
    void capacityStillRejectsWhenModelAgrees() {
        assertEquals(Decision.REJECT, matcher.match(
                attrs("Samsung", "990 PRO", "1TB"),
                attrs("Samsung", "990 PRO", "2TB"),
                "a", "b").decision());
    }

    /** Retailers write titles differently; a field only one side states proves nothing. */
    @Test
    void treatsAFieldMissingOnOneSideAsNeutral() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Samsung", "990 PRO", "1TB"),
                attrs("Samsung", "990 PRO", null),
                "Samsung 1TB 990 PRO SSD", "Samsung 990 PRO SSD");
        assertEquals(Decision.MATCH, outcome.decision());
    }

    /** Capacity spelled differently but equal after trim/case still matches. */
    @Test
    void comparesHardFieldsCaseInsensitively() {
        assertEquals(Decision.MATCH, matcher.match(
                attrs("Samsung", "990 pro", "1tb"),
                attrs("Samsung", "990 PRO", " 1TB "),
                "a", "b").decision());
    }

    @Test
    void matchesWhenNoHardFieldConflicts() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Sansui", "S55VOUG", null),
                attrs("Sansui", "S55VOUG", null),
                "Sansui S55VOUG 55\" OLED TV", "Sansui S55VOUG 4K Smart OLED TV");
        assertEquals(Decision.MATCH, outcome.decision());
    }

    /** A failed extraction must never be read as agreement. */
    @Test
    void isUncertainWhenEitherSideCouldNotBeExtracted() {
        assertEquals(Decision.UNCERTAIN, matcher.match(
                Optional.empty(), attrs("Samsung", "990 PRO", "1TB"), "a", "b").decision());
        assertEquals(Decision.UNCERTAIN, matcher.match(
                attrs("Samsung", "990 PRO", "1TB"), Optional.empty(), "a", "b").decision());
    }

    /**
     * The backfill's remaining failure mode: two unrelated products that state nothing
     * comparable conflict on nothing, and were matched on that basis alone. A tablet stand
     * with no extractable brand against a branded desk mount now stays uncertain.
     */
    @Test
    void isUncertainWhenNoHardFieldIsStatedOnBothSides() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs(null, null, null),
                attrs("CTA Digital", null, null),
                "Adjustable iPad Stand Tablet Holder 360°Rotation",
                "CTA Digital Triple-Enclosure Adjustable Desk Mount for 7 to 12\" Tablets");
        assertEquals(Decision.UNCERTAIN, outcome.decision());
        assertTrue(outcome.reason().contains("both sides"), outcome.reason());
    }

    /** One agreeing hard field is enough; the rest may be absent. */
    @Test
    void matchesOnASingleAgreeingHardField() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Sony", null, null),
                attrs("Sony", null, null),
                "Sony WH-1000XM6", "Sony WH-1000XM6 Headphones");
        assertEquals(Decision.MATCH, outcome.decision());
        assertTrue(outcome.reason().contains("agrees on brand"), outcome.reason());
    }

    /** Fallback only fires when neither title states anything at all. */
    @Test
    void fallsBackToTitleSimilarityOnlyWhenNeitherTitleHasAttributes() {
        Optional<ProductAttributes> none = Optional.of(ProductAttributes.empty());
        assertEquals(Decision.MATCH, matcher.match(none, none,
                "Wooden serving board", "Wooden serving board").decision());
    }

    @Test
    void staysUncertainWhenAttributelessTitlesAreOnlyLooselySimilar() {
        Optional<ProductAttributes> none = Optional.of(ProductAttributes.empty());
        ProductMatcher.Outcome outcome = matcher.match(none, none,
                "Wooden serving board", "Bamboo cutting block for kitchen");
        assertEquals(Decision.UNCERTAIN, outcome.decision());
    }

    @Test
    void stripsCodeFencesTheModelWasAskedNotToEmit() {
        assertEquals("{\"brand\":\"Samsung\"}",
                AttributeExtractor.stripCodeFence("```json\n{\"brand\":\"Samsung\"}\n```"));
        assertEquals("{\"a\":1}", AttributeExtractor.stripCodeFence("{\"a\":1}"));
    }
}
