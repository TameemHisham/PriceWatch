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
}
