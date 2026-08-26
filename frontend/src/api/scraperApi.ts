import type { CurrencyResponse } from "../types/CurrencyResponse";
import type { TrackedProductDetailResponse } from "../types/TrackedProductDetailResponse";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import type { TrackRequest } from "../types/TrackRequest";

const BASE = "/api/tracked-products";

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
        // fetch does NOT throw on 4xx/5xx
        throw new Error(await errorMessage(res));
    }
    return (await res.json()) as T;
}

/** GET every tracked product for the dashboard. Pass a signal to cancel on unmount. */
export function getTrackedProducts(
    options?: RequestInit,
): Promise<TrackedProductResponse[]> {
    // The list endpoint returns the flat DTO — no listings array.
    return jsonRequest<TrackedProductResponse[]>(BASE, options);
}

/** GET one tracked product by id for the detail page. Pass a signal to cancel on unmount. */
export function getTrackedProduct(
    id: number,
    options?: RequestInit,
): Promise<TrackedProductDetailResponse> {
    return jsonRequest<TrackedProductDetailResponse>(`${BASE}/${id}`, options);
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
export function refreshProduct(
    id: number,
): Promise<TrackedProductDetailResponse> {
    return jsonRequest<TrackedProductDetailResponse>(`${BASE}/${id}/refresh`, {
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
// Doesn't work because calling .json would cause an error to be thrown
// export async function deleteProduct(id: number): Promise<void> {
//     jsonRequest(`${BASE}/${id}`, { method: "DELETE" });
// }

/** Get current exchange rates */
export function getExchangeRates(
    options: RequestInit,
): Promise<CurrencyResponse[]> {
    return jsonRequest<CurrencyResponse[]>("/api/exchange-rates", options);
}
