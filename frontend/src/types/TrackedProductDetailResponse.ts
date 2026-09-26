import type { TrackedProductResponse } from "./TrackedProductResponse";

/** How a listing was attached. Only CROSS_STORE_DISCOVERY is inferred rather than exact. */
export type ListingOrigin =
    | "USER_SUBMITTED"
    | "SIBLING_MARKETPLACE"
    | "CROSS_STORE_DISCOVERY";

export interface ListingResponse {
    store: string;
    url: string;
    currency: string | null;
    currentPrice: number | null;
    marketplace: string;
    origin: ListingOrigin;
}

export interface TrackedProductDetailResponse extends TrackedProductResponse {
    listings: ListingResponse[];
    /** Same-retailer storefronts this product can still be opted into, minus those already tracked. */
    availableRegions: string[];
}
