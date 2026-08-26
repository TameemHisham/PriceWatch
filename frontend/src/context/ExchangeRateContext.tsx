import { createContext, useContext, useEffect, useState } from "react";
import type { CurrencyResponse } from "../types/CurrencyResponse";
import { getExchangeRates } from "../api/scraperApi";

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

    useEffect(() => {
        const controller = new AbortController();
        const fetchData = async () => {
            try {
                setError(null);
                const response: CurrencyResponse[] = await getExchangeRates({
                    signal: controller.signal,
                });
                setExchangeRate(response);
            } catch (err) {
                // Only update error state if the request wasn't intentionally aborted
                if (err instanceof Error && err.name !== "AbortError") {
                    setError(err.message);
                }
            }
        };
        fetchData();
        return () => {
            controller.abort();
        };
    }, []);
    return (
        <CurrencyExchangeContext.Provider value={exchangeRate}>
            {children}
        </CurrencyExchangeContext.Provider>
    );
}
