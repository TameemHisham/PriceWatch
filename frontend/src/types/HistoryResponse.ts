type HistoryPoint = { checkedAt: string; price: number; currency: string };
export type HistoryResponse = Record<string, HistoryPoint[]>;
