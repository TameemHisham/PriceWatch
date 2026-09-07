import { createContext, useContext, useState } from "react";
import type { ReactNode } from "react";

type AuthContextValue = {
    token: string | null;
    login: (token: string, persist: boolean) => void;
    logout: () => void;
    isAuthenticated: boolean;
};

// createContext just defines the "shape" of what will be shared — no real
// data yet. null is a placeholder until a <AuthContext.Provider> actually
// supplies real values further down the tree.
const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
    const [token, setToken] = useState<string | null>(
        () => localStorage.getItem("token") ?? sessionStorage.getItem("token"),
    );

    function login(newToken: string, persist: boolean) {
        if (persist) {
            localStorage.setItem("token", newToken);
        } else {
            sessionStorage.setItem("token", newToken);
        }
        setToken(newToken);
    }

    function logout() {
        localStorage.removeItem("token");
        sessionStorage.removeItem("token");
        setToken(null);
    }

    // .Provider is what actually makes { token, login, logout, isAuthenticated }
    // available to every component nested inside it — that's the whole point
    // of Context: skip manually passing these down as props through every
    // layer of the component tree.
    return (
        <AuthContext.Provider
            value={{ token, login, logout, isAuthenticated: token !== null }}
        >
            {children}
        </AuthContext.Provider>
    );
}

// useContext(AuthContext) reaches "up" the tree to find the nearest
// <AuthContext.Provider> and grabs its current value — this is how any
// component, no matter how deeply nested, can read the auth state without
// it being passed down as props through every layer in between.
//
// The `if (!ctx) throw` is a safety net: if useContext returns null, it
// means this hook got called OUTSIDE of <AuthProvider> — throwing here
// fails loudly right at the mistake, instead of silently returning
// undefined and crashing somewhere confusing later.
export function useAuth() {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error("useAuth must be used within AuthProvider");
    return ctx;
}
