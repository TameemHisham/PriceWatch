package com.tameem.pricewatch.matching;

/**
 * Structured attributes pulled out of a product title. Every field is optional: a title
 * only carries what it carries, and a field that is absent means "not stated", never
 * "different".
 */
public record ProductAttributes(
        String brand,
        String model,
        String capacity,
        String size,
        String color
) {

    public static ProductAttributes empty() {
        return new ProductAttributes(null, null, null, null, null);
    }

    /** True when the title yielded nothing usable — the only case that needs a fallback. */
    public boolean isEmpty() {
        return blank(brand) && blank(model) && blank(capacity) && blank(size) && blank(color);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
