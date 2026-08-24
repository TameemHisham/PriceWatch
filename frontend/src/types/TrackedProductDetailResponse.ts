import type { TrackedProductResponse } from "./TrackedProductResponse";

export interface ListingResponse {
    store: string;
    url: string;
    currency: string | null;
    currentPrice: number | null;
    marketplace: string;
}

export interface TrackedProductDetailResponse extends TrackedProductResponse {
    listings: ListingResponse[];
}
