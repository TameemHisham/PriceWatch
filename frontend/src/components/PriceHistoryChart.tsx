import { useEffect, useState } from "react";
import {
    LineChart,
    Line,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    Area,
    ReferenceLine,
    ResponsiveContainer,
} from "recharts";
import { marketplaceColor, marketplaceLabel } from "../utils/format";
import { getHistory } from "../api/scraperApi";
import type { HistoryResponse } from "../types/HistoryResponse";
import type { ChartPoint } from "../types/ChartPoint";
import type { TooltipPayloadItem } from "../types/TooltipPayloadItem";

function mergeHistory(history: HistoryResponse): {
    points: ChartPoint[];
    marketplaces: string[];
} {
    const marketplaces = Object.keys(history);
    const byDate = new Map<string, ChartPoint>();

    for (const marketplace of marketplaces) {
        for (const point of history[marketplace]) {
            const date = point.checkedAt.slice(0, 10);
            if (!byDate.has(date)) {
                byDate.set(date, { date, runningMin: null });
            }
            byDate.get(date)![marketplace] = point.price;
        }
    }

    const sorted = Array.from(byDate.values()).sort((a, b) =>
        a.date.localeCompare(b.date),
    );

    let runningMin: number | null = null;
    for (const point of sorted) {
        const pricesToday = marketplaces
            .map((m) => point[m])
            .filter((p): p is number => typeof p === "number");
        if (pricesToday.length > 0) {
            const todayMin = Math.min(...pricesToday);
            runningMin =
                runningMin === null ? todayMin : Math.min(runningMin, todayMin);
        }
        point.runningMin = runningMin;
    }

    return { points: sorted, marketplaces };
}

function CustomTooltip({
    active,
    payload,
    label,
}: {
    active?: boolean;
    payload?: TooltipPayloadItem[];
    label?: string;
}) {
    if (!active || !payload || payload.length === 0) return null;
    return (
        <div
            style={{
                background: "#1a1a1a",
                border: "1px solid #333",
                borderRadius: 8,
                padding: "10px 14px",
                fontSize: 13,
            }}
        >
            <div style={{ marginBottom: 6, color: "#aaa" }}>{label}</div>
            {payload
                .filter((p) => p.dataKey !== "runningMin" && p.value != null)
                .map((p) => (
                    <div
                        key={p.dataKey}
                        style={{
                            display: "flex",
                            gap: 8,
                            alignItems: "center",
                        }}
                    >
                        <span
                            style={{
                                width: 8,
                                height: 8,
                                borderRadius: "50%",
                                background: p.color,
                                display: "inline-block",
                            }}
                        />
                        <span>{marketplaceLabel(String(p.dataKey))}</span>
                        <strong style={{ marginLeft: "auto" }}>
                            {typeof p.value === "number"
                                ? p.value.toFixed(2)
                                : p.value}
                        </strong>
                    </div>
                ))}
        </div>
    );
}

export default function PriceHistoryChart({
    productId,
    targetPrice,
}: {
    productId: number;
    targetPrice: number | null;
}) {
    const [points, setPoints] = useState<ChartPoint[]>([]);
    const [marketplaces, setMarketplaces] = useState<string[]>([]);
    const [isOpen, setIsOpen] = useState(true);

    useEffect(() => {
        const controller = new AbortController();
        getHistory(productId, { signal: controller.signal })
            .then((data: HistoryResponse) => {
                const { points, marketplaces } = mergeHistory(data);
                setPoints(points);
                setMarketplaces(marketplaces);
            })
            .catch((err: unknown) => {
                if (err instanceof Error && err.name !== "AbortError")
                    console.error(err);
            });
        return () => controller.abort();
    }, [productId]);

    if (points.length === 0) {
        return (
            <div className="chart-card">
                <div className="chart-card__empty">No price history yet.</div>
            </div>
        );
    }

    function latestPrice(marketplace: string): number | null {
        for (let i = points.length - 1; i >= 0; i--) {
            const v = points[i][marketplace];
            if (typeof v === "number") return v;
        }
        return null;
    }

    return (
        <div className="chart-card">
            <div className="chart-card__header">
                <div>
                    <h3>Price history</h3>
                    <span className="chart-card__subtitle">
                        Last 90 days · lowest price shaded
                    </span>
                </div>
                <div className="chart-card__legend">
                    {marketplaces.map((m) => {
                        const price = latestPrice(m);
                        return (
                            <span key={m} className="legend-item">
                                <span
                                    className="legend-dot"
                                    style={{ background: marketplaceColor(m) }}
                                />
                                {marketplaceLabel(m)}
                                {price != null && (
                                    <strong>{price.toFixed(2)}</strong>
                                )}
                            </span>
                        );
                    })}
                </div>
                <button
                    className={`chart-card__toggle ${isOpen ? "chart-card__toggle--open" : ""}`}
                    onClick={() => setIsOpen((o) => !o)}
                    aria-label={isOpen ? "Collapse chart" : "Expand chart"}
                >
                    <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                    >
                        <polyline points="6 9 12 15 18 9" />
                    </svg>
                </button>
            </div>

            {isOpen && (
                <ResponsiveContainer width="100%" height={400}>
                    <LineChart data={points}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#333" />
                        <XAxis dataKey="date" stroke="#888" />
                        <YAxis stroke="#888" domain={["auto", "auto"]} />
                        <Tooltip content={<CustomTooltip />} />
                        <Area
                            type="monotone"
                            dataKey="runningMin"
                            fill="#888"
                            fillOpacity={0.12}
                            stroke="none"
                            isAnimationActive={false}
                        />
                        {marketplaces.map((marketplace) => (
                            <Line
                                key={marketplace}
                                type="linear"
                                dataKey={marketplace}
                                stroke={marketplaceColor(marketplace)}
                                dot={false}
                                connectNulls={false}
                                name={marketplaceLabel(marketplace)}
                            />
                        ))}
                        {targetPrice != null && (
                            <ReferenceLine
                                y={targetPrice}
                                stroke="#888"
                                strokeDasharray="3 3"
                                label={{
                                    value: `Target ${targetPrice}`,
                                    position: "insideTopRight",
                                    fill: "#888",
                                }}
                            />
                        )}
                    </LineChart>
                </ResponsiveContainer>
            )}
        </div>
    );
}
