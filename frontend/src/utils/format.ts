/** Formats a price for display, degrading to `amount CODE` when the currency is not valid ISO 4217. */
export function formatPrice(
    price: number | null,
    currency: string | null,
): string {
    if (price === null || currency === null) return "——";
    try {
        return new Intl.NumberFormat("en-US", {
            style: "currency",
            currency: currency,
        }).format(price);
    } catch {
        // if (err instanceof RangeError) return `${currency} ${price}`;
        // return `UNKNOWN ${price}`;
        return `${currency} ${price}`;
    }
}
const STORE_INFO: Record<string, { label: string; color: string }> = {
    AMAZON: {
        label: "Amazon",
        color: "oklch(0.76 0.15 55)",
    },
    EBAY: {
        label: "eBay",
        color: "oklch(0.70 0.15 256)",
    },
    NEWEGG: {
        label: "Newegg",
        color: "oklch(0.76 0.13 195)",
    },
    ALIEXPRESS: {
        label: "AliExpress",
        color: "oklch(0.78 0.14 30)",
    },
    WALMART: {
        label: "Walmart",
        color: "oklch(0.72 0.15 300)",
    },
    NOON: {
        label: "Noon",
        color: "oklch(0.74 0.16 45)",
    },
    CARREFOUR: {
        label: "Carrefour",
        color: "oklch(0.80 0.14 120)",
    },
};

const MARKETPLACE_LABELS: Record<string, string> = {
    AMAZON_UK: "Amazon UK",
    AMAZON_AE: "Amazon AE",
    AMAZON_US: "Amazon US",
};

export function marketplaceLabel(marketplace: string): string {
    return MARKETPLACE_LABELS[marketplace] ?? marketplace;
}

export function storeColor(store: string): string {
    return STORE_INFO[store]?.color ?? "var(--text-3)";
}
