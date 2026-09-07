import type { CurrencyResponse } from "../types/CurrencyResponse";

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
    BH_PHOTO: {
        label: "B&H Photo",
        color: "oklch(0.68 0.17 20)",
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

const MARKETPLACE_INFO: Record<string, { label: string; color: string }> = {
    AMAZON_UK: { label: "Amazon UK", color: "oklch(0.76 0.15 55)" },
    AMAZON_AE: { label: "Amazon AE", color: "oklch(0.70 0.14 85)" },
    AMAZON_US: { label: "Amazon US", color: "oklch(0.80 0.13 30)" },
    NEWEGG: { label: "Newegg", color: "oklch(0.76 0.13 195)" },
    BH_PHOTO: { label: "B&H Photo", color: "oklch(0.68 0.17 20)" },
};

export function marketplaceLabel(marketplace: string): string {
    return MARKETPLACE_INFO[marketplace]?.label ?? marketplace;
}

/** Colour for one marketplace line/dot. Distinct per storefront, not per retailer,
 *  so the three Amazon marketplaces stay tellable apart on the chart. */
export function marketplaceColor(marketplace: string): string {
    return MARKETPLACE_INFO[marketplace]?.color ?? "var(--text-3)";
}

export function storeColor(store: string): string {
    return STORE_INFO[store]?.color ?? "var(--text-3)";
}

/** Display name for a retailer, e.g. "BH_PHOTO" -> "B&H Photo". */
export function storeLabel(store: string): string {
    return STORE_INFO[store]?.label ?? store;
}

export function convertToUsd(
    price: number | null,
    currency: string | null,
    rates: CurrencyResponse[],
): number | null {
    if (!price) return null;
    if (!currency) return null;
    const exchangeRate: CurrencyResponse | undefined = rates.find(
        (el) => el.quote === currency,
    );
    if (!exchangeRate) return null;
    return price / exchangeRate.rate;
}
