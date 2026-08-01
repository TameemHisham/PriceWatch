import { NavLink } from "react-router-dom";
export default function Sidebar({
  theme,
  onThemeChange,
}: {
  theme: string;
  onThemeChange: () => void;
}) {
  return (
    <nav className="sidebar">
      <div className="logo--container">
        <div className="logo--icon">
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#fff"
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <polyline points="3 17 9 11 13 15 21 6"></polyline>
            <polyline points="15 6 21 6 21 12"></polyline>
          </svg>
        </div>
        <span className="logo--name">PriceWatch</span>
      </div>
      <NavLink to="/" end className="sidebar--btn">
        <svg
          width="17"
          height="17"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.9"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <rect x="3" y="3" width="7" height="7" rx="1.5"></rect>
          <rect x="14" y="3" width="7" height="7" rx="1.5"></rect>
          <rect x="3" y="14" width="7" height="7" rx="1.5"></rect>
          <rect x="14" y="14" width="7" height="7" rx="1.5"></rect>
        </svg>

        <span style={{ flex: "1" }}>Dashboard</span>
      </NavLink>
      <NavLink to="/alerts" className="sidebar--btn">
        <svg
          width="17"
          height="17"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.9"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M6 8a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8"></path>
          <path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"></path>
        </svg>

        <span style={{ flex: "1" }}>Alerts</span>
      </NavLink>
      <NavLink to="/add" className="sidebar--btn">
        <svg
          width="17"
          height="17"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.9"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="11" cy="11" r="7"></circle>
          <line x1="21" y1="21" x2="16" y2="16"></line>
          <line x1="11" y1="8" x2="11" y2="14"></line>
          <line x1="8" y1="11" x2="14" y2="11"></line>
        </svg>
        <span style={{ flex: "1" }}>Add product</span>
      </NavLink>
      <div style={{ flex: "1 1 0%" }}></div>
      <button className="theme--btn" onClick={onThemeChange}>
        {theme === "dark" ? (
          <>
            <span>☾</span>
            <span>Dark</span>
          </>
        ) : (
          <>
            <span>*</span>
            <span>Light</span>
          </>
        )}
      </button>
    </nav>
  );
}
