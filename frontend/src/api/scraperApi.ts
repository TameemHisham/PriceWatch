import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import type { TrackRequest } from "../types/TrackRequest";

const BASE = "/api/tracked-products";

// Shape of Spring's default error body. Every field is optional — a proxy or
// gateway can fail before Spring is ever reached.
type SpringError = {
    message?: string;
    errors?: { defaultMessage?: string }[];
};

/** Pulls the readable message out of Spring's error body, falling back to the status line. */
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

/** Fetches JSON and throws on any non-2xx — fetch itself does not reject on 4xx/5xx. */
async function jsonRequest<T>(url: string, options?: RequestInit): Promise<T> {
    const res = await fetch(url, options);
    if (!res.ok) {
        // fetch does NOT throw on 4xx/5xx — we do it ourselves.
        throw new Error(await errorMessage(res));
    }
    return (await res.json()) as T;
}

/** GET every tracked product for the dashboard. Pass a signal to cancel on unmount. */
export function getTrackedProducts(
    options?: RequestInit,
): Promise<TrackedProductResponse[]> {
    return jsonRequest<TrackedProductResponse[]>(BASE, options);
}

/** GET one tracked product by id for the detail page. Pass a signal to cancel on unmount. */
export function getTrackedProduct(
    id: number,
    options?: RequestInit,
): Promise<TrackedProductResponse> {
    return jsonRequest<TrackedProductResponse>(`${BASE}/${id}`, options);
}

/** POST a product URL to start tracking. Slow — it scrapes live. Do not abort: the insert completes anyway. */
export function trackProduct(url: string): Promise<TrackedProductResponse> {
    const payload: TrackRequest = { url };
    return jsonRequest<TrackedProductResponse>(BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
}

/** POST to re-scrape now and record a new price point. Do not abort: the write completes anyway. */
export function refreshProduct(id: number): Promise<TrackedProductResponse> {
    return jsonRequest<TrackedProductResponse>(`${BASE}/${id}/refresh`, {
        method: "POST",
    });
}

/** DELETE a tracked product. Returns 204 with an empty body, so no JSON parsing. */
export async function deleteProduct(id: number): Promise<void> {
    const res = await fetch(`${BASE}/${id}`, { method: "DELETE" });
    if (!res.ok) {
        throw new Error(await errorMessage(res));
    }
}
