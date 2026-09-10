package com.tameem.pricewatch.maintenance;

import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.service.TrackedProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time backfill: runs cross-store discovery against every existing product.
 * <p>
 * Products tracked before a storefront became searchable never had discovery run against
 * them, and neither trigger fires retroactively — initial track has already happened, and
 * manual refresh only covers products someone thinks to press refresh on. This closes that
 * gap once; it is not a scheduled job.
 * <p>
 * A runner rather than an endpoint, for two reasons. There is no admin role in this
 * application — every endpoint is merely "authenticated" — so an HTTP route touching every
 * user's products would let any logged-in account trigger work across everyone's data. And
 * the run takes minutes, which no HTTP client will wait for.
 * <p>
 * Inert unless {@code pricewatch.backfill-discovery=true} is passed at startup, and
 * defaults to a dry run even then.
 */
@Component
@ConditionalOnProperty(name = "pricewatch.backfill-discovery", havingValue = "true")
public class DiscoveryBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryBackfill.class);

    /**
     * Pause between products, sized by the LLM rate limit rather than by politeness.
     *
     * <p>Each product costs up to ten extraction calls — one for its own title, then one
     * per search hit across three searchable storefronts — and they are issued back to
     * back. Groq's free tier allows 30 requests a minute, so anything under roughly twenty
     * seconds per product exceeds it. Two seconds did: a first run lost 39 extractions to
     * HTTP 429 and a second lost 79, and because a failed extraction is reported as
     * "uncertain" rather than as an error, those losses looked exactly like products having
     * no match. The run appeared to succeed while silently measuring nothing.
     *
     * <p>It also keeps the retailer request rate low, which matters independently: a search
     * per storefront per product in a tight loop is the shape that trips bot detection, and
     * Newegg served a CAPTCHA after only a handful of search probes.
     */
    private static final long PAUSE_BETWEEN_PRODUCTS_MS = 22_000;

    private final TrackedProductRepository products;
    private final TrackedProductService service;
    private final boolean dryRun;

    public DiscoveryBackfill(TrackedProductRepository products, TrackedProductService service,
                             @Value("${pricewatch.backfill-discovery.dry-run:true}") boolean dryRun) {
        this.products = products;
        this.service = service;
        this.dryRun = dryRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<TrackedProduct> all = products.findAll();
        log.info("=== discovery backfill starting: {} products, dryRun={} ===", all.size(), dryRun);

        int examined = 0;
        int changed = 0;
        int failed = 0;

        for (TrackedProduct product : all) {
            examined++;
            try {
                // Each product is its own transaction: the loop does network I/O for
                // minutes, and holding one transaction open across all of it would pin a
                // connection and roll back everything on a single late failure.
                List<String> found = service.backfillDiscovery(product.getId(), dryRun);
                if (!found.isEmpty()) {
                    changed++;
                    log.info("[{}/{}] product {} \"{}\" -> {} {}",
                            examined, all.size(), product.getId(), shorten(product.getName()),
                            dryRun ? "WOULD attach" : "attached", found);
                } else {
                    log.info("[{}/{}] product {} \"{}\" -> no new matches",
                            examined, all.size(), product.getId(), shorten(product.getName()));
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[{}/{}] product {} failed: {}", examined, all.size(), product.getId(), e.toString());
            }
            try {
                Thread.sleep(PAUSE_BETWEEN_PRODUCTS_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Backfill interrupted after {} products", examined);
                break;
            }
        }

        log.info("=== discovery backfill complete: {} examined, {} {}, {} failed ===",
                examined, changed, dryRun ? "would change" : "changed", failed);
    }

    private static String shorten(String name) {
        if (name == null) return "";
        return name.length() <= 48 ? name : name.substring(0, 48) + "…";
    }
}
