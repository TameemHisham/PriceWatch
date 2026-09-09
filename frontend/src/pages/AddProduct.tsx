import { useState } from "react";
import Header from "../components/Header";
import { trackProduct, trackProductByName } from "../api/scraperApi";
import { ApiError } from "../api/ApiError";
import { useNavigate } from "react-router-dom";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";

/** A pasted link goes straight to the scraper; anything else is searched for by name. */
function looksLikeUrl(value: string): boolean {
    return /^https?:\/\//i.test(value.trim());
}

/** Add Product: tracks either a pasted product link or a product name, then opens it. */
export default function AddProduct() {
    const [query, setQuery] = useState<string>("");
    const [submitting, setSubmitting] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    // Distinct from `error`: nothing matched is an ordinary outcome, not a failure.
    const [notFound, setNotFound] = useState<string | null>(null);
    const navigate = useNavigate();

    const searchingByName = submitting && !looksLikeUrl(query);

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        const trimmed = query.trim();
        if (!trimmed) return;
        const byName = !looksLikeUrl(trimmed);
        try {
            setSubmitting(true);
            setError(null);
            setNotFound(null);
            const newTrackedProduct: TrackedProductResponse = byName
                ? await trackProductByName(trimmed)
                : await trackProduct(trimmed);
            navigate(`/product/${newTrackedProduct.id}`);
        } catch (err) {
            // 404 from the by-name flow means discovery found nothing, or every candidate
            // was correctly rejected by the attribute gate. Both deserve a way forward,
            // not an error banner.
            if (err instanceof ApiError && err.status === 404 && byName) {
                setNotFound(trimmed);
            } else {
                setError((err as Error).message);
            }
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div>
            <Header>
                <div className="header--desc">
                    <div className="header--title">Add product</div>
                    <span>
                        Paste a link, or type a product name to search stores
                    </span>
                </div>
            </Header>

            <div className="add-product--container">
                {notFound ? (
                    <div className="empty-state">
                        <div className="empty-state--title">
                            No match for “{notFound}”
                        </div>
                        <div className="empty-state--description">
                            We couldn't find that on any store we can search by
                            name. Try pasting a direct product link instead —
                            links work for every supported store.
                        </div>
                    </div>
                ) : null}

                {error ? (
                    <div className="error">
                        Something went wrong! <br />
                        <span className="error--detail">{error}</span>
                    </div>
                ) : null}

                <form className="add-product--form" onSubmit={handleSubmit}>
                    <label
                        htmlFor="add-product--input"
                        className="add-product--label"
                    >
                        Product link or search
                    </label>

                    <div className="add-product--input-container">
                        <div className="add-product--input-wrapper">
                            <svg
                                width="17"
                                height="17"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="var(--text-3)"
                                strokeWidth="2"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
                                <path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
                            </svg>

                            <input
                                id="add-product--input"
                                className="add-product--input"
                                placeholder="Paste a product link, or type a product name"
                                value={query}
                                onChange={(e) => setQuery(e.target.value)}
                                disabled={submitting}
                            />
                        </div>

                        <button
                            className="add-product--button"
                            type="submit"
                            disabled={submitting || !query.trim()}
                        >
                            {submitting ? "Working…" : "Find stores"}
                        </button>
                    </div>

                    {/* Honest status, not a fake progress bar. Searching by name really is
                        slower: it searches each store, then runs an attribute match over
                        every candidate it finds. */}
                    {submitting ? (
                        <div className="add-product--status" role="status">
                            {searchingByName
                                ? "Searching stores and matching… this takes a few seconds."
                                : "Fetching the product page…"}
                        </div>
                    ) : null}
                </form>

                {!submitting && !notFound && !error ? (
                    <div className="empty-state">
                        <div className="empty-state--icon">
                            <svg
                                width="24"
                                height="24"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="var(--text-3)"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" />
                                <path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" />
                            </svg>
                        </div>

                        <div className="empty-state--title">
                            Paste a link, or search by name
                        </div>

                        <div className="empty-state--description">
                            A link works for any supported store. A name is
                            searched across the stores we can search, and only
                            confident matches are attached.
                        </div>
                    </div>
                ) : null}
            </div>
        </div>
    );
}
