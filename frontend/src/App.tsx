import { useEffect, useState } from "react";
import Sidebar from "./components/Sidebar";

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

  function onThemeChange() {
    setTheme(theme === "dark" ? "light" : "dark");
  }
  return (
    <div className="app-container">
      <Sidebar theme={theme} onThemeChange={onThemeChange} />
      <main>
        <h1>Hello there!!</h1>
      </main>
    </div>
  );
}

export default App;
