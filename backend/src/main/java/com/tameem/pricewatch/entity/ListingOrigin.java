package com.tameem.pricewatch.entity;

/**
 * How a listing came to be attached to a product.
 * <p>
 * Two of these are auto-attached but they are not equally trustworthy, which is the whole
 * reason this is an enum rather than a boolean: a sibling marketplace is the same product
 * by construction, while a cross-store match was inferred and could be wrong.
 */
public enum ListingOrigin {

    /** The URL the user pasted. */
    USER_SUBMITTED,

    /** Same product id on another storefront of the same retailer — exact, not inferred. */
    SIBLING_MARKETPLACE,

    /** Found by searching other stores and clearing the attribute gate — a judgement call. */
    CROSS_STORE_DISCOVERY
}
