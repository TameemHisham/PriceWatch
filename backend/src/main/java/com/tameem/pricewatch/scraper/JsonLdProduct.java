package com.tameem.pricewatch.scraper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * schema.org Product data lifted out of a page's {@code <script type="application/ld+json">}
 * blocks.
 * <p>
 * Preferred over CSS selectors as a price source: retailers keep this markup stable because
 * Google's rich results depend on it, whereas a class name can be renamed or build-hashed
 * with no external consequence — B&amp;H's own price element carries the generated class
 * {@code price__9gLfjPSjp}.
 * <p>
 * Every accessor is best-effort: a page with malformed or absent JSON-LD yields an empty
 * Optional rather than throwing, so callers can fall back to selectors.
 */
public record JsonLdProduct(
        String name,
        BigDecimal price,
        String currency,
        String availability,
        String sku,
        String mpn,
        String url,
        String image
) {

    private static final Logger log = LoggerFactory.getLogger(JsonLdProduct.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** First schema.org Product found in the document, if any block parses and carries one. */
    public static Optional<JsonLdProduct> from(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String raw = script.data();
            if (raw == null || raw.isBlank()) continue;

            JsonNode root;
            try {
                root = MAPPER.readTree(raw.trim());
            } catch (RuntimeException e) {
                // A malformed block is not a page failure — other blocks may still be valid.
                log.debug("Skipping unparseable ld+json block: {}", e.toString());
                continue;
            }

            for (JsonNode candidate : candidates(root)) {
                if (isProduct(candidate)) {
                    return Optional.of(read(candidate));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The nodes worth testing for @type Product: the root itself, the members of a top-level
     * array, and the members of an "@graph" wrapper. These are the shapes retailers actually
     * emit.
     */
    private static List<JsonNode> candidates(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        out.add(root);
        if (root.isArray()) {
            for (JsonNode n : root) out.add(n);
        }
        JsonNode graph = root.path("@graph");
        if (graph.isArray()) {
            for (JsonNode n : graph) out.add(n);
        }
        return out;
    }

    /** {@code @type} is usually a string, but the spec allows a list of types. */
    private static boolean isProduct(JsonNode node) {
        if (!node.isObject()) return false;
        JsonNode type = node.path("@type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("Product".equals(t.asString())) return true;
            }
            return false;
        }
        return "Product".equals(type.asString());
    }

    private static JsonLdProduct read(JsonNode product) {
        JsonNode offer = firstOfferWithPrice(product.path("offers"));
        return new JsonLdProduct(
                text(product.path("name")),
                parsePrice(text(offer.path("price"))),
                text(offer.path("priceCurrency")),
                shortAvailability(text(offer.path("availability"))),
                text(product.path("sku")),
                text(product.path("mpn")),
                text(product.path("url")),
                image(product.path("image"))
        );
    }

    /** "offers" is an Offer, a list of Offers, or an AggregateOffer wrapping them. */
    private static JsonNode firstOfferWithPrice(JsonNode offers) {
        if (offers.isArray()) {
            for (JsonNode o : offers) {
                if (!text(o.path("price")).isEmpty()) return o;
            }
            return offers.isEmpty() ? offers : offers.get(0);
        }
        if (!text(offers.path("price")).isEmpty()) return offers;
        // AggregateOffer keeps the real offers one level down.
        JsonNode nested = offers.path("offers");
        if (!nested.isMissingNode()) return firstOfferWithPrice(nested);
        return offers;
    }

    /** "image" may be a single URL or a list; take the first usable one. */
    private static String image(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode n : node) {
                String v = text(n);
                if (!v.isEmpty()) return v;
            }
            return null;
        }
        String v = text(node);
        return v.isEmpty() ? null : v;
    }

    /** "https://schema.org/InStock" -> "InStock". */
    private static String shortAvailability(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int slash = raw.lastIndexOf('/');
        String value = slash >= 0 ? raw.substring(slash + 1) : raw;
        return value.isBlank() ? null : value.trim();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String value = node.isObject() || node.isArray() ? "" : node.asString();
        return value == null ? "" : value.trim();
    }

    /** Schema prices are plain decimal strings, but retailers still emit grouping commas. */
    private static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replaceAll("[^0-9.]", "");
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.debug("ld+json price not numeric: {}", raw);
            return null;
        }
    }

    /** True when this block actually carried a usable price. */
    public boolean hasPrice() {
        return price != null;
    }

    /** Availability strings that mean the offer is live and its price is real. */
    public boolean isPurchasable() {
        if (availability == null) return price != null;
        return switch (availability) {
            case "InStock", "InStoreOnly", "OnlineOnly", "PreOrder",
                 "PreSale", "BackOrder", "LimitedAvailability" -> true;
            default -> false;
        };
    }
}
