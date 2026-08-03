import { useEffect, useState } from "react";
import { Navigate, useParams } from "react-router-dom";
// import { useNavigate } from "react-router-dom";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import { getTrackedProduct } from "../api/scraperApi";
import Header from "../components/Header";

/** Product detail for /product/:id. Redirects home when the id is missing or not a number. */
export default function ProductDetail() {
    const { id } = useParams();
    const productId = Number(id);
    const validId = id !== undefined && !Number.isNaN(productId);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [product, setProduct] = useState<TrackedProductResponse | null>(null);

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

    return (
        <div>
            <Header>
                <button>Dashboard</button>
                <button>Watching</button>
            </Header>
            <div className="center" style={{ flex: 1 }}>
                {loading ? (
                    <div className="center">
                        <div className="loader"></div>
                    </div>
                ) : error ? (
                    <div className="error">
                        Something went wrong! <br />
                        <span className="error--detail">{error}</span>
                    </div>
                ) : (
                    <pre>{JSON.stringify(product)}</pre>
                )}
            </div>
        </div>
    );
}
