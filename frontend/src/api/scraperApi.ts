import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import type { TrackRequest } from "../types/TrackRequest";

const BASE = "/api/tracked-products";

// Shared helper: the 4 steps for any call that returns JSON.
// <T> = the shape we expect back, so callers get a typed result.
// Shape of Spring's default error body. Every field is optional — a proxy or
// gateway can fail before Spring is ever reached.
type SpringError = {
    message?: string;
    errors?: { defaultMessage?: string }[];
};

// The useful text lives in the BODY, not on the Response object, so it has to be
// awaited. An error response is not guaranteed to be JSON (a dead proxy returns
// HTML), so parsing must never throw — fall back to the status line.
async function errorMessage(res: Response): Promise<string> {
    const status = `Request failed: ${res.status} ${res.statusText}`;
    try {
        const body: SpringError = await res.json();
        // Validation failures land in errors[]; everything else in message.
        const detail = body.errors?.[0]?.defaultMessage ?? body.message;
        return detail ? `${status} — ${detail}` : status;
    } catch {
        return status;
    }
}

async function jsonRequest<T>(url: string, options?: RequestInit): Promise<T> {
    const res = await fetch(url, options);
    if (!res.ok) {
        // fetch does NOT throw on 4xx/5xx — we do it ourselves.
        throw new Error(await errorMessage(res));
    }
    return (await res.json()) as T;
}

// GET /api/tracked-products  -> array of cards for the dashboard
export function getTrackedProducts(
    options?: RequestInit,
): Promise<TrackedProductResponse[]> {
    return jsonRequest<TrackedProductResponse[]>(BASE, options);
}

// GET /api/tracked-products/{id}  -> one product for the detail page
export function getTrackedProduct(
    id: number,
    options?: RequestInit,
): Promise<TrackedProductResponse> {
    return jsonRequest<TrackedProductResponse>(`${BASE}/${id}`, options);
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
        throw new Error(await errorMessage(res));
    }
}
