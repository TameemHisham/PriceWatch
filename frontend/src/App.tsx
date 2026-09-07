import { useEffect, useState } from "react";
import Sidebar from "./components/Sidebar";
import { Route, Routes, useLocation } from "react-router-dom";
import Dashboard from "./pages/Dashboard";
import ProductDetail from "./pages/ProductDetail";
import AddProduct from "./pages/AddProduct";
import Alerts from "./pages/Alerts";
import NotFound from "./pages/NotFound";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import ExchangeRateProvider from "./context/ExchangeRateContext";
import { AuthProvider } from "./context/AuthContext";
import ProtectedRoute from "./pages/ProtectedRoute";

function App() {
    const [theme, setTheme] = useState<string>(
        window.matchMedia("(prefers-color-scheme: dark)").matches
            ? "dark"
            : "light",
    );
    useEffect(
        () => document.documentElement.setAttribute("data-theme", theme),
        [theme],
    );

    const isHome = useLocation().pathname === "/";

    function onThemeChange() {
        setTheme(theme === "dark" ? "light" : "dark");
    }

    return (
        <AuthProvider>
            <ExchangeRateProvider>
                <div className={`app--container ${isHome ? "is-home" : ""}`}>
                    <Sidebar theme={theme} onThemeChange={onThemeChange} />
                    <main className="">
                        <Routes>
                            <Route path="/login" element={<LoginPage />} />
                            <Route
                                path="/register"
                                element={<RegisterPage />}
                            />

                            <Route
                                path="/"
                                element={
                                    <ProtectedRoute>
                                        <Dashboard />
                                    </ProtectedRoute>
                                }
                            />
                            <Route
                                path="/product/:id"
                                element={
                                    <ProtectedRoute>
                                        <ProductDetail />
                                    </ProtectedRoute>
                                }
                            />
                            <Route
                                path="/add"
                                element={
                                    <ProtectedRoute>
                                        <AddProduct />
                                    </ProtectedRoute>
                                }
                            />
                            <Route
                                path="/alerts"
                                element={
                                    <ProtectedRoute>
                                        <Alerts />
                                    </ProtectedRoute>
                                }
                            />

                            <Route path="*" element={<NotFound />} />
                        </Routes>
                    </main>
                </div>
            </ExchangeRateProvider>
        </AuthProvider>
    );
}

export default App;
