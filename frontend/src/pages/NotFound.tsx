export default function NotFound() {
    return (
        <div className="center" style={{ minHeight: "100vh", fontWeight: 600 }}>
            <div>
                <span>Page not found!</span>
                <br />
                <a className="link center" href="/Dashboard">
                    Home
                </a>
            </div>
        </div>
    );
}
