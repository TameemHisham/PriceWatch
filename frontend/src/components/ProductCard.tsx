import { useNavigate } from "react-router-dom";
import type { TrackedProductResponse } from "../types/TrackedProductResponse";
import { formatPrice } from "../utils/format";

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
            className="product-card"
            onClick={() => navigate(`/product/${product.id}`)}
        >
            <div className="product-card--image">
                {/* <img src={product.imageUrl ?? ""} alt="Image of product" /> */}
            </div>
            <div className="product-card--body">
                <div className="product-card--brand">
                    {product.brand ?? "——"}
                </div>
                {/* <div className="product-card--name">{product.name.slice(0, 35)}</div> */}
                <div className="product-card--name">{product.name}</div>
                <div className="product-card--price">
                    {formatPrice(product.currentPrice, product.currency)}
                </div>
                {/* <div className="product-card--trend">{}</div> */}
                <div className="product-card--stores">
                    {product.storeCount === 1
                        ? `${product.storeCount} Store`
                        : `${product.storeCount} Stores`}
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
