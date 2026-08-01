import { useEffect, useState } from "react";
import { getTrackedProducts } from "../api/scraperApi";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import ProductCard from "../components/ProductCard";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";

export default function Dashboard() {
    const [products, setProducts] = useState<TrackedProductResponse[]>([]);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [query, setQuery] = useState<string>("");
    const [layout, setLayout] = useState<"grid" | "list">("list");
    const navigate = useNavigate();

    useEffect(() => {
        const controller = new AbortController();
        const fetchData = async () => {
            try {
                setLoading(true);
                setError(null);
                const response: TrackedProductResponse[] =
                    await getTrackedProducts({ signal: controller.signal });
                setProducts(response);
            } catch (err) {
                // Only update error state if the request wasn't intentionally aborted
                if (err instanceof Error && err.name !== "AbortError") {
                    setError(err.message);
                }
            } finally {
                // Only change loading state if the component is still listening
                if (!controller.signal.aborted) {
                    setLoading(false);
                }
            }
        };
        fetchData();
        return () => {
            controller.abort();
        };
    }, []);
    const filtered: TrackedProductResponse[] = products.filter(
        (products) =>
            products.name.toLowerCase().includes(query.toLowerCase()) ||
            products.brand?.toLowerCase().includes(query.toLowerCase()) ||
            products.category?.toLowerCase().includes(query.toLowerCase()),
    );
    return (
        <div>
            <Header>
                <div className="header--desc">
                    <div className="header--title">Dashboard</div>
                    <span>
                        Tracking {products.length} products and{" "}
                        {products.reduce(
                            (acc, product) => product.storeCount + acc,
                            0,
                        )}{" "}
                        stores
                    </span>
                </div>
                <div className="filter--container">
                    <svg
                        width="15"
                        height="15"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="var(--text-3)"
                        strokeWidth="2"
                        strokeLinecap="round"
                    >
                        <circle cx="11" cy="11" r="7"></circle>
                        <line x1="21" y1="21" x2="16" y2="16"></line>
                    </svg>
                    <input
                        type="search"
                        name="filter"
                        id="filter"
                        placeholder="Search products"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                    />
                </div>
                <div className="view-toggle">
                    <button
                        title="Grid"
                        className={`view-toggle--button ${layout === "grid" ? "active" : ""}`}
                        onClick={() => setLayout("grid")}
                    >
                        <svg
                            width="16"
                            height="16"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        >
                            <rect x="3" y="3" width="7" height="7" rx="1.5" />
                            <rect x="14" y="3" width="7" height="7" rx="1.5" />
                            <rect x="3" y="14" width="7" height="7" rx="1.5" />
                            <rect x="14" y="14" width="7" height="7" rx="1.5" />
                        </svg>
                    </button>

                    <button
                        title="List"
                        className={`view-toggle--button ${layout === "list" ? "active" : ""}`}
                        onClick={() => setLayout("list")}
                    >
                        <svg
                            width="16"
                            height="16"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        >
                            <line x1="8" y1="6" x2="21" y2="6" />
                            <line x1="8" y1="12" x2="21" y2="12" />
                            <line x1="8" y1="18" x2="21" y2="18" />
                            <line x1="3.5" y1="6" x2="3.5" y2="6" />
                            <line x1="3.5" y1="12" x2="3.5" y2="12" />
                            <line x1="3.5" y1="18" x2="3.5" y2="18" />
                        </svg>
                    </button>
                </div>
                <button onClick={() => navigate("/add")}>+ Add product</button>
            </Header>
            {loading ? (
                <div className="center">
                    <div className="loader"></div>
                </div>
            ) : error ? (
                <div className="error">
                    Something went wrong! <br />
                    <span className="error--detail">{error}</span>
                </div>
            ) : products.length === 0 ? (
                <div className="empty-state">
                    <button
                        className="empty-state--icon"
                        onClick={() => navigate("/add")}
                    >
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
                    </button>

                    <div className="empty-state--title">
                        Paste a product link to get started
                    </div>

                    <div className="empty-state--description">
                        We'll match it across all supported stores and begin
                        tracking the price history right away.
                    </div>
                </div>
            ) : filtered.length === 0 ? (
                <div className="empty-state ">no results for that search!</div>
            ) : (
                <div className="product--container">
                    <div className={`product--${layout}`}>
                        {layout === "list" && (
                            <div className="products-header">
                                <span className="products-header--title">
                                    Product
                                </span>
                                <span className="products-header--title">
                                    Lowest
                                </span>
                                <span className="products-header--title">
                                    Store
                                </span>
                                <span className="products-header--title">
                                    Trend
                                </span>
                            </div>
                        )}
                        {filtered.map((product) => (
                            <ProductCard
                                product={product}
                                key={product.id}
                                layout={layout}
                            />
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
