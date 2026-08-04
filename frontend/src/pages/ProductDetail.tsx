import { useEffect, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import type { TrackedProductDetailResponse } from "../types/TrackedProductDetailResponse";
import { getTrackedProduct, refreshProduct } from "../api/scraperApi";
import { formatPrice, storeColor, storeLabel } from "../utils/format";
import Header from "../components/Header";

/** Product detail for /product/:id. Redirects home when the id is missing or not a number. */
export default function ProductDetail() {
    const { id } = useParams();
    const productId = Number(id);
    const validId = id !== undefined && !Number.isNaN(productId);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [product, setProduct] = useState<TrackedProductDetailResponse | null>(
        null,
    );
    const [refreshing, setRefreshing] = useState<boolean>(false);

    useEffect(() => {
        if (!validId) return;

        const controller = new AbortController();
        const fetchData = async () => {
            try {
                setLoading(true);
                setError(null);
                const response = await getTrackedProduct(productId, {
                    signal: controller.signal,
                });
                setProduct(response);
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
    }, [productId, validId]);

    // `replace` swaps the history entry so Back doesn't return to the broken URL.
    if (!validId) return <Navigate to="/" replace />;

    async function handleRefresh() {
        try {
            setRefreshing(true);
            setError(null);
            const response: TrackedProductDetailResponse =
                await refreshProduct(productId);
            setProduct(response);
        } catch (err) {
            if (err instanceof Error) setError(err.message);
        } finally {
            setRefreshing(false);
        }
    }

    return (
        <div>
            <Header>
                {/* <div className="productDetail--header"> */}
                <Link to="/" className="dashboard-button">
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
                        <line x1="19" y1="12" x2="5" y2="12" />
                        <polyline points="11 18 5 12 11 6" />
                    </svg>
                    Dashboard
                </Link>
                <div style={{ flex: 1 }}></div>
                <button
                    className="refresh-button"
                    onClick={handleRefresh}
                    disabled={refreshing}
                >
                    {/* Stroke-based like every other icon here, so it inherits the
                        button's colour through currentColor instead of painting black. */}
                    <svg
                        className={refreshing ? "spinning" : ""}
                        width="15"
                        height="15"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    >
                        <polyline points="23 4 23 10 17 10" />
                        <polyline points="1 20 1 14 7 14" />
                        <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10" />
                        <path d="M20.49 15a9 9 0 0 1-14.85 3.36L1 14" />
                    </svg>
                    <span>Reload</span>
                </button>
                <button className="product-card--watch-button">
                    <svg
                        width="15"
                        height="15"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    >
                        <path d="M19 21l-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />
                    </svg>

                    <span>Watching</span>
                </button>
                {/* </div> */}
            </Header>
            {loading ? (
                <div className="center" style={{ flex: 1 }}>
                    <div className="loader"></div>
                </div>
            ) : error ? (
                <div className="error">
                    Something went wrong! <br />
                    <span className="error--detail">{error}</span>
                </div>
            ) : !product ? null : (
                <div className="page--container">
                    <div className="product-details">
                        <div className="product-details--image">
                            {product.category && (
                                <span className="product-details--category-label">
                                    {product.category}
                                </span>
                            )}
                        </div>

                        <div className="product-details--content">
                            <div>
                                {product.brand && (
                                    <div className="product-details--brand">
                                        {product.brand}
                                    </div>
                                )}

                                <h1 className="product-details--title">
                                    {product.name}
                                </h1>

                                <div className="product-details--tags">
                                    {product.category && (
                                        <span className="product-details--tag">
                                            {product.category}
                                        </span>
                                    )}
                                    <span className="product-details--tag">
                                        {product.storeCount}{" "}
                                        {product.storeCount === 1
                                            ? "store"
                                            : "stores"}
                                    </span>
                                </div>
                            </div>

                            <div className="product-details--footer">
                                <div>
                                    <div className="product-details--label">
                                        Lowest right now
                                    </div>

                                    <div className="product-details--price">
                                        <span className="product-details--price-value">
                                            {formatPrice(
                                                product.currentPrice,
                                                product.currency,
                                            )}
                                        </span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div className="store-prices">
                        <div className="store-prices--table">
                            <div className="store-prices--header">
                                Current price by store
                            </div>

                            <div className="store-prices--headings">
                                <span>Store</span>
                                <span>Price</span>
                                <span></span>
                            </div>

                            {[...product.listings]
                                .sort((a, b) => {
                                    if (a.currentPrice === null) return 1;
                                    if (b.currentPrice === null) return -1;
                                    return a.currentPrice - b.currentPrice;
                                })
                                .map((row, i) => (
                                    <div
                                        className="store-prices--row"
                                        key={row.store}
                                    >
                                        <div className="store-prices--store">
                                            <span
                                                className="store-prices--store-color"
                                                style={{
                                                    background: storeColor(
                                                        row.store,
                                                    ),
                                                }}
                                            />

                                            <span className="store-prices--store-name">
                                                {storeLabel(row.store)}
                                            </span>

                                            {i === 0 && (
                                                <span className="store-prices--lowest">
                                                    LOWEST
                                                </span>
                                            )}
                                        </div>

                                        <span className="store-prices--price">
                                            {formatPrice(
                                                row.currentPrice,
                                                row.currency,
                                            )}
                                        </span>

                                        <a
                                            href={row.url}
                                            className="store-prices--link"
                                            target="_blank"
                                            rel="noopener noreferrer"
                                        >
                                            Visit
                                            <svg
                                                width="13"
                                                height="13"
                                                viewBox="0 0 24 24"
                                                fill="none"
                                                stroke="currentColor"
                                                strokeWidth="2"
                                                strokeLinecap="round"
                                                strokeLinejoin="round"
                                            >
                                                <line
                                                    x1="7"
                                                    y1="17"
                                                    x2="17"
                                                    y2="7"
                                                />
                                                <polyline points="7 7 17 7 17 17" />
                                            </svg>
                                        </a>
                                    </div>
                                ))}
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
