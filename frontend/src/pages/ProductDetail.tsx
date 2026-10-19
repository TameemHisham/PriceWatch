import { useEffect, useState } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import type { TrackedProductDetailResponse } from "../types/TrackedProductDetailResponse";
import {
    addRegion,
    deleteProduct,
    getTrackedProduct,
    refreshProduct,
    retryDiscovery,
} from "../api/scraperApi";
import {
    formatPrice,
    marketplaceColor,
    marketplaceLabel,
    convertToUsd,
} from "../utils/format";
import Header from "../components/Header";
import { useExchangeRates } from "../context/ExchangeRateContext";
import type { CurrencyResponse } from "../types/CurrencyResponse";
import TargetPriceCard from "../components/TargetPriceCard";
import PriceHistoryChart from "../components/PriceHistoryChart";

/** Product detail for /product/:id. Redirects home when the id is missing or not a number. */
export default function ProductDetail() {
    const rates: CurrencyResponse[] | null = useExchangeRates();
    const { id } = useParams();
    const productId = Number(id);
    const validId = id !== undefined && !Number.isNaN(productId);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [product, setProduct] = useState<TrackedProductDetailResponse | null>(
        null,
    );
    const [refreshing, setRefreshing] = useState<boolean>(false);
    const [deleting, setDeleting] = useState<boolean>(false);
    // The region a chip is currently adding, and per-chip errors keyed by marketplace id —
    // a failed region shows its message on the chip itself, not as a page-level error.
    const [addingRegion, setAddingRegion] = useState<string | null>(null);
    const [regionErrors, setRegionErrors] = useState<Record<string, string>>(
        {},
    );
    const navigate = useNavigate();
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

    // Cross-store discovery runs asynchronously (Phase 5, via Kafka), so the first fetch comes
    // back with discoveryStatus === "PENDING" and only the primary listing. Poll for completion:
    // we setProduct only when the status actually flips (DONE/FAILED) — while it stays PENDING
    // this effect's own timer chain keeps going with a 2s→5s backoff, and stops the moment the
    // status changes, on unmount, or after a 2-minute cap.
    const discoveryStatus = product?.discoveryStatus;
    useEffect(() => {
        if (discoveryStatus !== "PENDING") return;
        let cancelled = false;
        const deadline = Date.now() + 120_000;
        let delay = 2000;
        let timer: ReturnType<typeof setTimeout>;
        const tick = async () => {
            if (cancelled || Date.now() > deadline) return;
            try {
                const fresh = await getTrackedProduct(productId);
                if (cancelled) return;
                if (fresh.discoveryStatus !== "PENDING") {
                    setProduct(fresh); // DONE/FAILED → show the result and stop polling
                    return;
                }
            } catch {
                // transient error — keep polling
            }
            delay = Math.min(delay + 1000, 5000);
            timer = setTimeout(tick, delay);
        };
        timer = setTimeout(tick, delay);
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [productId, discoveryStatus]);

    // `replace` swaps the history entry so Back doesn't return to the broken URL.
    if (!validId) return <Navigate to="/" replace />;
    // Re-run discovery after a FAILED one. Flipping status to PENDING locally restarts the
    // polling effect, so the banner switches back to "Searching…" immediately.
    async function handleRetryDiscovery() {
        if (!product) return;
        try {
            setError(null);
            await retryDiscovery(productId);
            setProduct({ ...product, discoveryStatus: "PENDING" });
        } catch (err) {
            if (err instanceof Error) setError(err.message);
        }
    }
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
    // Opt the product into another same-retailer storefront, then refresh so the new listing
    // and the updated chip list both appear. Errors stay on the chip.
    async function handleAddRegion(marketplaceId: string) {
        try {
            setAddingRegion(marketplaceId);
            setRegionErrors((prev) => {
                const next = { ...prev };
                delete next[marketplaceId];
                return next;
            });
            await addRegion(productId, marketplaceId);
            const response = await getTrackedProduct(productId);
            setProduct(response);
        } catch (err) {
            const message =
                err instanceof Error ? err.message : "Could not add region";
            setRegionErrors((prev) => ({ ...prev, [marketplaceId]: message }));
        } finally {
            setAddingRegion(null);
        }
    }
    async function handleDelete() {
        if (
            !window.confirm(
                "Stop tracking this product? Its price history will be deleted.",
            )
        )
            return;
        try {
            setDeleting(true);
            await deleteProduct(productId);
            navigate("/");
        } catch (err) {
            setDeleting(false);
            if (err instanceof Error) setError(err.message);
        }
    }

    return (
        <div>
            <Header>
                {/* <div className="productDetail--header"> */}
                <Link to="/" className="header--button">
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
                    <span>Dashboard</span>
                </Link>
                <div className="header--product-name">{product?.name}</div>
                <div className="header--spacer"></div>
                <button
                    className="header--button"
                    onClick={handleRefresh}
                    disabled={refreshing}
                >
                    <svg
                        className={refreshing ? "is-spinning" : ""}
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
                <button
                    className="header--button is-danger"
                    onClick={handleDelete}
                    disabled={deleting}
                >
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
                        <polyline points="3 6 5 6 21 6" />
                        <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
                        <path d="M10 11v6M14 11v6" />
                        <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
                    </svg>
                    <span>{deleting ? "Deleting…" : "Delete"}</span>
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
                <div className="page--container is-narrow">
                    <div className="product-details">
                        <div className="product-details--image">
                            {product.imageUrl && (
                                <img
                                    className="thumb"
                                    src={product.imageUrl}
                                    alt=""
                                    loading="lazy"
                                    onError={(e) => {
                                        e.currentTarget.style.display = "none";
                                    }}
                                />
                            )}
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
                    <PriceHistoryChart
                        productId={product.id}
                        targetPrice={product.targetPrice}
                    />

                    <div className="detail-grid">
                        <div className="store-prices">
                            <div className="store-prices--table">
                                <div className="store-prices--header">
                                    Current price by store
                                </div>

                                {product.discoveryStatus === "PENDING" && (
                                    <div
                                        className="store-prices--discovery"
                                        style={{
                                            padding: "8px 16px",
                                            fontSize: "0.85rem",
                                            opacity: 0.7,
                                        }}
                                    >
                                        Searching other stores…
                                    </div>
                                )}
                                {product.discoveryStatus === "FAILED" && (
                                    <div
                                        className="store-prices--discovery"
                                        style={{
                                            padding: "8px 16px",
                                            fontSize: "0.85rem",
                                            color: "#c0392b",
                                        }}
                                    >
                                        Couldn’t search other stores this time.{" "}
                                        <button
                                            type="button"
                                            onClick={handleRetryDiscovery}
                                            style={{
                                                background: "none",
                                                border: "none",
                                                color: "inherit",
                                                textDecoration: "underline",
                                                cursor: "pointer",
                                                padding: 0,
                                                font: "inherit",
                                            }}
                                        >
                                            Retry
                                        </button>
                                    </div>
                                )}

                                <div className="store-prices--headings">
                                    <span>Store</span>
                                    <span>Price</span>
                                    <span></span>
                                </div>

                                {[...product.listings]
                                    .sort((a, b) => {
                                        if (rates === null) return 0; // no rates, just don't sort
                                        const priceA = convertToUsd(
                                            a.currentPrice,
                                            a.currency,
                                            rates,
                                        );
                                        const priceB = convertToUsd(
                                            b.currentPrice,
                                            b.currency,
                                            rates,
                                        );
                                        if (priceA === null) return 1;
                                        if (priceB === null) return -1;
                                        return priceA - priceB;
                                    })
                                    .map((row, i) => (
                                        <div
                                            className={
                                                row.currentPrice === null
                                                    ? "store-prices--row store-prices--row--unavailable"
                                                    : "store-prices--row"
                                            }
                                            key={row.marketplace}
                                        >
                                            <div className="store-prices--store">
                                                <span
                                                    className="store-prices--store-color"
                                                    style={{
                                                        background: marketplaceColor(
                                                            row.marketplace,
                                                        ),
                                                    }}
                                                />

                                                <span className="store-prices--store-name">
                                                    {/* {storeLabel(row.store)} */}
                                                    {marketplaceLabel(
                                                        row.marketplace,
                                                    )}
                                                </span>

                                                {/* Guarded on the row's own price too:
                                                    when every listing is out of stock the
                                                    comparator has nothing to order by, and
                                                    row 0 would otherwise be badged LOWEST
                                                    with no price at all. */}
                                                {rates !== null &&
                                                    i === 0 &&
                                                    row.currentPrice !== null && (
                                                        <span className="store-prices--lowest">
                                                            LOWEST
                                                        </span>
                                                    )}

                                                {/* Only cross-store matches are badged. A
                                                    sibling marketplace is the same product
                                                    by construction; this one was inferred,
                                                    so it is the one worth being able to
                                                    see and question. */}
                                                {row.origin ===
                                                    "CROSS_STORE_DISCOVERY" && (
                                                    <span
                                                        className="store-prices--found"
                                                        title="We found this listing by searching other stores and matching it to this product"
                                                    >
                                                        FOUND FOR YOU
                                                    </span>
                                                )}
                                            </div>

                                            {/* The listing is kept deliberately: we are
                                                watching this store and it has no offer
                                                right now, which is an observation rather
                                                than an absence. A bare dash did not say
                                                that; hiding the row would have said the
                                                opposite. */}
                                            {row.currentPrice === null ? (
                                                <span className="store-prices--unavailable">
                                                    Not available
                                                </span>
                                            ) : (
                                                <span className="store-prices--price">
                                                    {formatPrice(
                                                        row.currentPrice,
                                                        row.currency,
                                                    )}
                                                </span>
                                            )}

                                            <a
                                                href={row.url}
                                                className="store-prices--link"
                                                target="_blank"
                                                rel="noopener noreferrer"
                                            >
                                                <span>Visit</span>
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

                                {product.availableRegions.length > 0 && (
                                    <div className="store-prices--also-check">
                                        <span className="store-prices--also-check-label">
                                            Also check:
                                        </span>
                                        <div className="store-prices--chips">
                                            {product.availableRegions.map(
                                                (region) => (
                                                    <div
                                                        className="store-prices--chip-wrap"
                                                        key={region}
                                                    >
                                                        <button
                                                            type="button"
                                                            className="store-prices--chip"
                                                            disabled={
                                                                addingRegion !==
                                                                null
                                                            }
                                                            onClick={() =>
                                                                handleAddRegion(
                                                                    region,
                                                                )
                                                            }
                                                        >
                                                            <span
                                                                className="store-prices--chip-color"
                                                                style={{
                                                                    background:
                                                                        marketplaceColor(
                                                                            region,
                                                                        ),
                                                                }}
                                                            />
                                                            <span>
                                                                {addingRegion ===
                                                                region
                                                                    ? "Adding…"
                                                                    : `+ ${marketplaceLabel(region)}`}
                                                            </span>
                                                        </button>
                                                        {regionErrors[
                                                            region
                                                        ] && (
                                                            <span className="store-prices--chip-error">
                                                                {
                                                                    regionErrors[
                                                                        region
                                                                    ]
                                                                }
                                                            </span>
                                                        )}
                                                    </div>
                                                ),
                                            )}
                                        </div>
                                    </div>
                                )}
                            </div>
                        </div>
                        <TargetPriceCard
                            productId={product.id}
                            currentTarget={product.targetPrice}
                            onSaved={(newTarget) =>
                                setProduct({
                                    ...product,
                                    targetPrice: newTarget,
                                })
                            }
                        />
                    </div>
                </div>
            )}
        </div>
    );
}
