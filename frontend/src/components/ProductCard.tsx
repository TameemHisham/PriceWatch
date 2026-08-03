import { useNavigate } from "react-router-dom";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";

/** One product as a grid card or a list row, chosen by the `layout` prop. */
export default function ProductCard({
    product,
    layout,
}: {
    product: TrackedProductResponse;
    layout: "grid" | "list";
}) {
    const navigate = useNavigate();

    return layout === "grid" ? (
        <div
            className="product--card"
            onClick={() => navigate(`/product/${product.id}`)}
        >
            <div className="product--image">
                {/* <img src={product.imageUrl ?? ""} alt="Image of product" /> */}
            </div>
            <div className="product--card-inner">
                <div className="product--brand">{product.brand ?? "——"}</div>
                {/* <div className="product--name">{product.name.slice(0, 35)}</div> */}
                <div className="product--name">{product.name}</div>
                <div className="product--price">
                    {formatPrice(product.currentPrice, product.currency)}
                </div>
                {/* <div className="product--trend">{}</div> */}
                <div className="product--stores">
                    {product.storeCount} Stores
                </div>
            </div>
        </div>
    ) : (
        <div
            className="product-row"
            onClick={() => navigate(`/product/${product.id}`)}
        >
            <div className="product-row--info">
                <div className="product-row--image"></div>

                <div className="product-row--details">
                    <div className="product-row--name">{product.name}</div>

                    <div className="product-row--brand">
                        {product.brand ?? "——"}
                    </div>
                </div>
            </div>

            <div className="product-row--price">
                <span className="product-row--price-value">
                    {formatPrice(product.currentPrice, product.currency)}
                </span>

                <span className="product-row--price-indicator"></span>
            </div>

            <span className="product-row--store">Amazon</span>

            <div className="product-row--trend">--</div>
        </div>
    );
}

/** Formats a price for display, degrading to `amount CODE` when the currency is not valid ISO 4217. */
function formatPrice(price: number | null, currency: string | null): string {
    if (price === null || currency === null) return "——";
    try {
        return new Intl.NumberFormat("en-US", {
            style: "currency",
            currency: currency,
        }).format(price);
    } catch {
        // if (err instanceof RangeError) return `${currency} ${price}`;
        // return `UNKNOWN ${price}`;
        return `${currency} ${price}`;
    }
}
