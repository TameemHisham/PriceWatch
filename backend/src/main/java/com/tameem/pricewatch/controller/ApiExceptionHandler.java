package com.tameem.pricewatch.controller;

import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.scraper.UnsupportedMarketplaceException;
import com.tameem.pricewatch.service.IllegalURLFormat;
import com.tameem.pricewatch.service.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns the exceptions this API throws into responses that carry a readable reason.
 * <p>
 * Without this, a handled exception falls through to the servlet container's /error
 * forward. That dispatch runs the security chain again without re-establishing the JWT
 * identity, so every failure came back as a bare 403 with an empty body regardless of the
 * status the exception declared. Returning a ResponseEntity here means the error forward
 * never happens at all; permitting the ERROR dispatch in SecurityConfig covers whatever
 * still reaches it.
 * <p>
 * The body shape is {@code {"message": "..."}} — the same shape AuthController's own
 * handlers use, and the one the frontend's errorMessage() already reads.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, Exception e,
                                                            String fallback) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? fallback
                : e.getMessage();
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    /** The product exists for someone, but not for the caller — or not at all. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e, "Not found");
    }

    /** A URL for a storefront that is not configured. The caller can fix this. */
    @ExceptionHandler(UnsupportedMarketplaceException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedMarketplace(
            UnsupportedMarketplaceException e) {
        return body(HttpStatus.BAD_REQUEST, e, "This storefront is not supported yet");
    }

    /** A supported storefront, but the URL carries no product id. Also the caller's to fix. */
    @ExceptionHandler(IllegalURLFormat.class)
    public ResponseEntity<Map<String, String>> handleIllegalUrl(IllegalURLFormat e) {
        return body(HttpStatus.BAD_REQUEST, e, "That URL is not a trackable product link");
    }

    /**
     * Everything else that went wrong while reading a retailer's page: a bot challenge, a
     * changed page shape, a failed fetch. 502 rather than 500 because the failure is
     * upstream of this service, not a defect in handling the request.
     * <p>
     * Declared after the two subclasses above; Spring dispatches to the most specific
     * handler, so UnsupportedMarketplaceException still resolves to 400.
     */
    @ExceptionHandler(ScrapeException.class)
    public ResponseEntity<Map<String, String>> handleScrapeFailure(ScrapeException e) {
        log.warn("Scrape failed: {}", e.toString());
        return body(HttpStatus.BAD_GATEWAY, e, "Could not read the product page");
    }

    /**
     * A row the database refused — in practice a unique constraint, such as a second listing
     * for a product on a marketplace it already has. The service checks for that before
     * inserting, so reaching here means a route that check does not cover; 409 says the
     * request conflicts with what is already stored rather than that the server broke.
     * <p>
     * Deliberately not using {@link #body}: the exception's own message carries the failing
     * SQL and the generated constraint name, which is a schema detail no client should see.
     * The detail goes to the log instead.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(
            DataIntegrityViolationException e) {
        log.warn("Constraint violation: {}", e.getMostSpecificCause().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "That is already tracked"));
    }
}
