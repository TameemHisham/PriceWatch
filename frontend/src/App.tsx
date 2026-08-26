import { useEffect, useState } from "react";
import Sidebar from "./components/Sidebar";
import { Route, Routes, useLocation } from "react-router-dom";
import Dashboard from "./pages/Dashboard";
import ProductDetail from "./pages/ProductDetail";
import AddProduct from "./pages/AddProduct";
import Alerts from "./pages/Alerts";
import NotFound from "./pages/NotFound";
import ExchangeRateProvider from "./context/ExchangeRateContext";

/** App shell: owns the theme, renders the sidebar, and maps URLs to pages. */
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

    // The mobile mockup shows the PriceWatch bar on the home screen only;
    // every other screen owns its full height. CSS cannot see the route, so
    // the shell carries it as a class.
    const isHome = useLocation().pathname === "/";

    function onThemeChange() {
        setTheme(theme === "dark" ? "light" : "dark");
    }
    return (
        <div className={`app--container ${isHome ? "is-home" : ""}`}>
            <Sidebar theme={theme} onThemeChange={onThemeChange} />
            <main className="">
                <ExchangeRateProvider>
                    <Routes>
                        <Route path="/" element={<Dashboard />} />
                        <Route
                            path="/product/:id"
                            element={<ProductDetail />}
                        />
                        <Route path="/add" element={<AddProduct />} />
                        <Route path="/alerts" element={<Alerts />} />
                        <Route path="*" element={<NotFound />} />
                    </Routes>
                </ExchangeRateProvider>
            </main>
        </div>
    );
}

export default App;
