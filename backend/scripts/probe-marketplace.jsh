// probe-marketplace.jsh — a Jsoup probe using the same HTTP stack the scrapers use, so it
// reproduces exactly what they see at a storefront's edge: the HTTP status, the cf-mitigated
// response header, and the page <title> (a "Just a moment…" title is the Cloudflare tell).
//
// Usage (run from the backend/ directory):
//   PROBE_URL="https://www.currys.co.uk/products/..." \
//     jshell --class-path "$(find ~/.m2/repository/org/jsoup -name 'jsoup-*.jar' | head -1)" \
//            scripts/probe-marketplace.jsh
//
// Optional proxy — credentials come from the environment ONLY, never hardcode them here:
//   PROBE_PROXY_HOST=proxy.example.net PROBE_PROXY_PORT=8080 \
//   PROBE_PROXY_USER=... PROBE_PROXY_PASS=... \
//   PROBE_URL="https://..." jshell --class-path "<jsoup jar>" scripts/probe-marketplace.jsh

import org.jsoup.Jsoup;
import org.jsoup.Connection;

// The JDK disables Basic auth on HTTPS tunnelling (CONNECT) proxies by default; residential
// proxy pools need it. Cleared before any connection is opened.
System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

String url = System.getenv("PROBE_URL");
if (url == null || url.isBlank()) {
    System.out.println("Set PROBE_URL to the page to probe — see the usage comment at the top of this script.");
} else {
    Connection connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-GB,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .ignoreHttpErrors(true)   // read the block off the response instead of throwing on a 403
            .ignoreContentType(true)  // some endpoints answer JSON
            .maxBodySize(0)
            .timeout(15000);

    String proxyHost = System.getenv("PROBE_PROXY_HOST");
    String proxyPort = System.getenv("PROBE_PROXY_PORT");
    if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null && !proxyPort.isBlank()) {
        connection.proxy(proxyHost, Integer.parseInt(proxyPort));
        String proxyUser = System.getenv("PROBE_PROXY_USER");
        String proxyPass = System.getenv("PROBE_PROXY_PASS");
        if (proxyUser != null && !proxyUser.isBlank()) {
            connection.auth(ctx -> ctx.isProxy() ? ctx.credentials(proxyUser, proxyPass) : null);
        }
        System.out.println("Via proxy:    " + proxyHost + ":" + proxyPort);
    } else {
        System.out.println("Via proxy:    (none — set PROBE_PROXY_HOST/PORT to route through one)");
    }

    Connection.Response response = connection.execute();
    String mitigated = response.header("cf-mitigated");
    String title = response.parse().title();
    System.out.println("URL:          " + url);
    System.out.println("HTTP status:  " + response.statusCode() + " " + response.statusMessage());
    System.out.println("cf-mitigated: " + (mitigated == null || mitigated.isBlank() ? "(absent)" : mitigated));
    System.out.println("Page title:   " + (title == null || title.isBlank() ? "(none)" : title));
}

/exit
