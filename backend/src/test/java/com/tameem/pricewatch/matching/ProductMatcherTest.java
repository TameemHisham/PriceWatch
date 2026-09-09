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

    @Test
    void rejectsOnModelDisagreement() {
        ProductMatcher.Outcome outcome = matcher.match(
                attrs("Apple", "iPhone 17 Pro Max", "512 GB"),
                attrs("Apple", "iPhone 17 Pro", "512 GB"),
                "Apple iPhone 17 Pro Max 512 GB", "Apple iPhone 17 Pro 512 GB");
        assertEquals(Decision.REJECT, outcome.decision());
        assertTrue(outcome.reason().contains("model"), outcome.reason());
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
