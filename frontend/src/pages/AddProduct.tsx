import { useState } from "react";
import Header from "../components/Header";
import { trackProduct } from "../api/scraperApi";
// import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import { useNavigate } from "react-router-dom";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";

/** Add Product: submits a URL to be scraped and tracked, then returns to the dashboard. */
export default function AddProduct() {
    const [query, setQuery] = useState<string>("");
    const [submitting, setSubmitting] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    const navigate = useNavigate();

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        try {
            setSubmitting(true);
            setError(null);
            const newTrackedProduct: TrackedProductResponse =
                await trackProduct(query);
            navigate(`/product/${newTrackedProduct.id}`);
            // setQuery("");
            // navigate(`/`);
        } catch (err) {
            setError((err as Error).message);
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
                        Paste a link and we'll find it across every store
                    </span>
                </div>
            </Header>
            {error ? (
                <div className="error">
                    Something went wrong! <br />
                    <span className="error--detail">{error}</span>
                </div>
            ) : (
                <div className="add-product--container">
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
                                    placeholder="Paste a product link"
                                    value={query}
                                    onChange={(e) => setQuery(e.target.value)}
                                    disabled={submitting}
                                />
                            </div>

                            <button
                                className="add-product--button"
                                type="submit"
                                disabled={submitting}
                            >
                                Find stores
                            </button>
                        </div>
                    </form>

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
                            Paste a product link to get started
                        </div>

                        <div className="empty-state--description">
                            We'll match it across all supported stores and begin
                            tracking the price history right away.
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
