import { createContext, useContext, useEffect, useState } from "react";
import type { CurrencyResponse } from "../types/CurrencyResponse";
import { getExchangeRates } from "../api/scraperApi";
import { useAuth } from "./AuthContext";

const CurrencyExchangeContext = createContext<CurrencyResponse[] | null>(null);
export function useExchangeRates(): CurrencyResponse[] | null {
    return useContext(CurrencyExchangeContext);
}

export default function ExchangeRateProvider({
    children,
}: {
    children: React.ReactNode;
}) {
    const [exchangeRate, setExchangeRate] = useState<CurrencyResponse[] | null>(
        null,
    );
    const [error, setError] = useState<string | null>(null);
    const { isAuthenticated } = useAuth();

    useEffect(() => {
        // Nothing to fetch if nobody's logged in — and firing this pre-login
        // is exactly what was hitting the backend's 403 wall.
        if (!isAuthenticated) return;

        const controller = new AbortController();
        const fetchData = async () => {
            try {
                setError(null);
                const response: CurrencyResponse[] = await getExchangeRates({
                    signal: controller.signal,
                });
                setExchangeRate(response);
            } catch (err) {
                if (err instanceof Error && err.name !== "AbortError") {
                    setError(err.message);
                }
            }
        };
        fetchData();
        return () => {
            controller.abort();
        };
    }, [isAuthenticated]);

    return (
        <CurrencyExchangeContext.Provider value={exchangeRate}>
            {children}
        </CurrencyExchangeContext.Provider>
    );
}
