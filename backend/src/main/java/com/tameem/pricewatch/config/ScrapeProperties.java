package com.tameem.pricewatch.config;

import org.jsoup.Connection;
import org.slf4j.Logger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.jsoup.helper.RequestAuthenticator;
@Component
@ConfigurationProperties(prefix = "pricewatch.scrape")
public class ScrapeProperties {

    /** Insertion-ordered so URL matching is deterministic when hosts overlap. */
    private Map<String, MarketplaceConfig> marketplaces = new LinkedHashMap<>();

    public Map<String, MarketplaceConfig> getMarketplaces() {
        return marketplaces;
    }

    public void setMarketplaces(Map<String, MarketplaceConfig> marketplaces) {
        this.marketplaces = marketplaces;
    }

    public static class MarketplaceConfig {
        /** Domain that identifies this storefront in a product URL. */
        private String host;
        /**
         * Whether this storefront is scraped and searched at all. Default true. Set false to
         * park a marketplace whose edge check the scrapers cannot pass — B&amp;H's Cloudflare
         * JS challenge is the first — without deleting its scraper or its stored listings and
         * price history. Discovery skips a disabled marketplace when fanning out searches, and
         * the scheduler skips its listings on each sweep.
         */
        private boolean enabled = true;
        /** ISO country this storefront is being priced for. */
        private String deliveryCountry;
        /** Sent as Accept-Language so the storefront does not guess locale from IP. */
        private String acceptLanguage = "en-GB,en;q=0.9";
        /** Zero or more proxy endpoints for this marketplace; empty means scrape from local egress. */
        private List<ProxyEndpoint> proxyPool = List.of();
        public record ProxyEndpoint(String host, int port, String username, String password) {}
        /**
         * Public API key for the storefront's hosted search provider, where searching it
         * means calling that provider rather than fetching a results page. Configuration
         * rather than a constant because the key is embedded in the storefront's own
         * front-end bundle and rotates with its deploys — a rotation should be a property
         * change, not a code change. Blank for every storefront searched by fetching HTML.
         */
        private String searchKey;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getDeliveryCountry() { return deliveryCountry; }
        public void setDeliveryCountry(String deliveryCountry) { this.deliveryCountry = deliveryCountry; }

        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }

        public List<ProxyEndpoint> getProxyPool() { return proxyPool; }
        public void setProxyPool(List<ProxyEndpoint> proxyPool) { this.proxyPool = proxyPool; }

        public String getSearchKey() { return searchKey; }
        public void setSearchKey(String searchKey) { this.searchKey = searchKey; }

        /**
         * Applies a random proxy from this marketplace's pool to the connection, or logs and
         * leaves it unset when the pool is empty — the one place every scraper's fetch()
         * decides how to reach a storefront, so proxy selection lives here instead of being
         * repeated per scraper.
         */
        public void applyProxy(Connection connection, Logger log) {
            if (!proxyPool.isEmpty()) {
                ProxyEndpoint proxy = proxyPool.get(ThreadLocalRandom.current().nextInt(proxyPool.size()));
                connection.proxy(proxy.host(), proxy.port());
                connection.auth(ctx -> ctx.isProxy()
                        ? ctx.credentials(proxy.username(), proxy.password())
                        : null);
                log.debug("Using proxy {}:{} for delivery country {}", proxy.host(), proxy.port(), deliveryCountry);
            } else {
                log.debug("No proxy for delivery country {} — scraping from local egress", deliveryCountry);
            }
        }
    }
}