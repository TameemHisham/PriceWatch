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
}
