import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import type { TrackRequest } from "../types/TrackRequest";

const BASE = "/api/tracked-products";

// Shared helper: the 4 steps for any call that returns JSON.
// <T> = the shape we expect back, so callers get a typed result.
async function jsonRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, options);
  if (!res.ok) {
    // fetch does NOT throw on 4xx/5xx — we do it ourselves.
    throw new Error(`Request failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

// GET /api/tracked-products  -> array of cards for the dashboard
export function getTrackedProducts(): Promise<TrackedProductResponse[]> {
  return jsonRequest<TrackedProductResponse[]>(BASE);
}

// GET /api/tracked-products/{id}  -> one product for the detail page
export function getTrackedProduct(id: number): Promise<TrackedProductResponse> {
  return jsonRequest<TrackedProductResponse>(`${BASE}/${id}`);
}

// POST /api/tracked-products  -> scrape + track a new url
export function trackProduct(url: string): Promise<TrackedProductResponse> {
  const payload: TrackRequest = { url };
  return jsonRequest<TrackedProductResponse>(BASE, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
}

// POST /api/tracked-products/{id}/refresh  -> re-scrape, new price point
export function refreshProduct(id: number): Promise<TrackedProductResponse> {
  return jsonRequest<TrackedProductResponse>(`${BASE}/${id}/refresh`, {
    method: "POST",
  });
}

// DELETE /api/tracked-products/{id}  -> 204, empty body (do NOT parse json)
export async function deleteProduct(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`Delete failed: ${res.status} ${res.statusText}`);
  }
}
