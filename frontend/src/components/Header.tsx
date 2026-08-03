/** Sticky page header shell — each page passes its own contents as children. */
export default function Header({ children }: { children: React.ReactNode }) {
    return <div className="header">{children}</div>;
}
