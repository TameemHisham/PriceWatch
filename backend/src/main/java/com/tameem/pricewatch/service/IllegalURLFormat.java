package com.tameem.pricewatch.service;

public class IllegalURLFormat extends RuntimeException {
    public IllegalURLFormat(String message) {
        super(message);
    }
    public IllegalURLFormat(String message, Throwable cause) {
        super(message, cause);
    }
}