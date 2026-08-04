import type { TrackedProductResponse } from "./TrackedProductResponse";

export interface ListingResponse {
    store: string;
    url: string;
    currency: string | null;
    currentPrice: number | null;
}

export interface TrackedProductDetailResponse extends TrackedProductResponse {
    listings: ListingResponse[];
}
