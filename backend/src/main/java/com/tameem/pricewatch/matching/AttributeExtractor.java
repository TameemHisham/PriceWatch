package com.tameem.pricewatch.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts structured attributes from a product title with one call to Gemini
 * 2.5 Flash-Lite.
 * <p>
 * The prompt is the one already validated against real catalogue titles — in particular
 * its instruction never to infer a value that is not in the text, which is what makes an
 * absent field mean "not stated" rather than "different".
 */
@Component
public class AttributeExtractor {

    private static final Logger log = LoggerFactory.getLogger(AttributeExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = "gemini-2.5-flash-lite";

    private static final String PROMPT_TEMPLATE = """
            Extract product attributes from this title as a JSON object.
            Only include fields that are ACTUALLY PRESENT in the title text — do not guess or infer a value that isn't there.

            Fields to look for:
            - brand: the manufacturer/brand name
            - model: the specific model name/number (not the brand)
            - capacity: any quantifiable spec — storage (GB/TB), volume (L/ml), weight (g/kg), count (e.g. "11-piece", "Pack of 5"), or similar
            - size: physical dimensions, screen size, or clothing/shoe size if present
            - color: the color/finish

            Title: "%s"

            Respond with ONLY the raw JSON object. No markdown, no code fences, no explanation.""";

    /**
     * Titles already extracted. The free tier allows very few calls, and the same title is
     * asked about repeatedly — once as the tracked product, then again as a search hit on
     * every later track — so repeating those calls wastes the budget that makes the feature
     * work at all. Only successful extractions are cached; a failure must stay retryable.
     */
    private final Map<String, ProductAttributes> cache = new ConcurrentHashMap<>();

    private final RestClient client;
    private final String apiKey;

    public AttributeExtractor(@Value("${pricewatch.gemini.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.client = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/models")
                .build();
    }

    /** True when a key is configured; without one, matching degrades to "uncertain". */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Attributes for a title, or empty when extraction could not be performed.
     * <p>
     * An empty Optional means "we do not know", which callers must treat as uncertain —
     * distinct from a present-but-empty ProductAttributes, which means the model read the
     * title and found nothing to extract.
     */
    public Optional<ProductAttributes> extract(String title) {
        if (title == null || title.isBlank()) {
            return Optional.of(ProductAttributes.empty());
        }
        if (!isConfigured()) {
            log.warn("No pricewatch.gemini.api-key configured — title attributes cannot be "
                    + "extracted, so cross-store matching will not auto-attach anything");
            return Optional.empty();
        }
        ProductAttributes cached = cache.get(title);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String body = client.post()
                    .uri("/{model}:generateContent?key={key}", MODEL, apiKey)
                    .body(Map.of("contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", PROMPT_TEMPLATE.formatted(title)))))))
                    .retrieve()
                    .body(String.class);
            ProductAttributes parsed = parse(body);
            cache.put(title, parsed);
            return Optional.of(parsed);
        } catch (RuntimeException e) {
            log.warn("Attribute extraction failed for title '{}': {}", title, e.toString());
            return Optional.empty();
        }
    }

    private ProductAttributes parse(String responseBody) {
        JsonNode root = MAPPER.readTree(responseBody);
        String text = root.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asString();
        JsonNode attrs = MAPPER.readTree(stripCodeFence(text));
        return new ProductAttributes(
                field(attrs, "brand"),
                field(attrs, "model"),
                field(attrs, "capacity"),
                field(attrs, "size"),
                field(attrs, "color"));
    }

    /** The prompt forbids fences, but models add them anyway. */
    static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        String[] parts = trimmed.split("```");
        String inner = parts.length > 1 ? parts[1] : "";
        if (inner.startsWith("json")) {
            inner = inner.substring(4);
        }
        return inner.trim();
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull() || value.isObject() || value.isArray()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text.trim();
    }
}
