import type { CurrencyResponse } from "../types/CurrencyResponse";
import type { TrackedProductDetailResponse } from "../types/TrackedProductDetailResponse";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import type { TrackRequest } from "../types/TrackRequest";
import type { TrackByNameRequest } from "../types/TrackByNameRequest";
import { ApiError } from "./ApiError";
import type { HistoryResponse } from "../types/HistoryResponse";
import type { AuthRequest, AuthResponse } from "../types/AuthResponse";
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
        const detail = body.errors?.[0]?.defaultMessage ?? body.message;
        if (detail) return detail;
    } catch {
        return status;
    }
    return `Request failed: ${res.status} ${res.statusText}`;
}

/** Reads the stored token, checking localStorage (persisted) then sessionStorage (this tab only). */
function getToken(): string | null {
    return localStorage.getItem("token") ?? sessionStorage.getItem("token");
}

/** Clears the token from both storages and sends the user back to login — used on 401. */
function forceLogout() {
    localStorage.removeItem("token");
    sessionStorage.removeItem("token");
    window.location.href = "/login";
}

/** Fetches JSON and throws on any non-2xx — fetch itself does not reject on 4xx/5xx.
 *  Attaches the auth token if one exists, and forces logout on 401. */
async function jsonRequest<T>(url: string, options?: RequestInit): Promise<T> {
    const token = getToken();
    const res = await fetch(url, {
        ...options,
        headers: {
            ...options?.headers,
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
    });
    if (res.status === 401) {
        forceLogout();
    }
    if (!res.ok) {
        // fetch does NOT throw on 4xx/5xx. Carry the status: callers need to tell an
        // ordinary "nothing matched" 404 apart from an actual failure.
        throw new ApiError(await errorMessage(res), res.status);
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

/**
 * POST a product NAME to start tracking. Slower than trackProduct: the backend searches
 * every searchable store, then runs an LLM attribute gate over each candidate. Answers 404
 * when nothing matched, which is an ordinary outcome rather than a failure.
 */
export function trackProductByName(
    name: string,
): Promise<TrackedProductResponse> {
    const payload: TrackByNameRequest = { name };
    return jsonRequest<TrackedProductResponse>(`${BASE}/by-name`, {
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
    const token = getToken();
    const res = await fetch(`${BASE}/${id}`, {
        method: "DELETE",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (res.status === 401) {
        forceLogout();
    }
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

/** Like jsonRequest, but never force-logs-out on 401 — a failed login attempt
 *  isn't an expired session, it's just wrong credentials, and should just
 *  surface the error without navigating anywhere. */
async function authRequest<T>(url: string, options: RequestInit): Promise<T> {
    const res = await fetch(url, options);
    if (!res.ok) {
        throw new Error(await errorMessage(res));
    }
    return (await res.json()) as T;
}

export function register(
    email: string,
    password: string,
): Promise<AuthResponse> {
    const payload: AuthRequest = { email, password };
    return authRequest<AuthResponse>("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
}

export function login(email: string, password: string): Promise<AuthResponse> {
    const payload: AuthRequest = { email, password };
    return authRequest<AuthResponse>("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
}
export async function setTargetPrice(
    id: number,
    targetPrice: number,
): Promise<void> {
    await jsonRequest<void>(`${BASE}/${id}/target`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ targetPrice }),
    });
}

export function getHistory(
    id: number,
    options?: RequestInit,
): Promise<HistoryResponse> {
    return jsonRequest<HistoryResponse>(`${BASE}/${id}/history`, options);
}
