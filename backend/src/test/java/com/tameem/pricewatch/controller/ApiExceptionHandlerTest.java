package com.tameem.pricewatch.controller;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The safety net: a constraint the service did not check for must not reach the client raw. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static final String RAW = "could not execute statement [ERROR: duplicate key value "
            + "violates unique constraint \"ukcupyehhxn6mo94km94jsdlywj\" Detail: Key "
            + "(tracked_product_id, marketplace)=(44, AMAZON_UK) already exists.]";

    @Test
    void mapsAConstraintViolationTo409() {
        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException(RAW));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("That is already tracked", response.getBody().get("message"));
    }

    /** The generated constraint name and the failing SQL are schema detail, not client detail. */
    @Test
    void doesNotLeakTheUnderlyingSqlOrConstraintName() {
        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException(RAW));

        String message = response.getBody().get("message");
        assertFalse(message.contains("ukcupyehhxn6mo94km94jsdlywj"), message);
        assertFalse(message.contains("tracked_product_id"), message);
        assertFalse(message.toLowerCase().contains("constraint"), message);
    }
}
