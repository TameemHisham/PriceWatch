/** Where cross-store discovery is for this product (Phase 5, async via Kafka). */
export type DiscoveryStatus = "PENDING" | "DONE" | "FAILED";

export interface TrackedProductResponse {
    id: number;
    name: string;
    brand: string | null;
    category: string | null;
    targetPrice: number | null;
    createdAt: string;
    imageUrl: string | null;
    currency: string | null;
    currentPrice: number | null;
    storeCount: number;
    stores: string[];
    discoveryStatus: DiscoveryStatus | null;
}
