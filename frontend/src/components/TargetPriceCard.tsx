import { useState } from "react";

export default function TargetPriceCard({
    productId,
    currentTarget,
    onSaved,
}: {
    productId: number;
    currentTarget: number | null;
    onSaved: (newTarget: number) => void;
}) {
    const [value, setValue] = useState(currentTarget?.toString() ?? "");
    const [saving, setSaving] = useState(false);

    async function handleSetAlert() {
        const targetPrice = Number(value);
        if (Number.isNaN(targetPrice)) return;
        setSaving(true);
        try {
            await fetch(`/api/tracked-products/${productId}/target`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ targetPrice }),
            });
            onSaved(targetPrice);
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="target-price-card">
            <div className="target-price-card__title">
                <span className="target-price-card__icon">◎</span>
                Target price alert
            </div>
            <div className="target-price-card__row">
                <div className="target-price-card__label">
                    Alert me at or below
                </div>
                <div className="target-price-card__value">
                    {currentTarget != null ? `$${currentTarget}` : "Not set"}
                </div>
            </div>
            <div className="target-price-card__form">
                <span className="target-price-card__prefix">$</span>
                <input
                    type="number"
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                />
                <button onClick={handleSetAlert} disabled={saving}>
                    {saving ? "Saving…" : "Set alert"}
                </button>
            </div>
            <p className="target-price-card__hint">
                We'll notify you the moment any tracked store drops to or below
                this price.
            </p>
        </div>
    );
}
