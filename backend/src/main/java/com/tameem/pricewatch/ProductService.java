package com.tameem.pricewatch;


import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }
    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }
    public Product createProduct(Product product) {
        return productRepository.save(product); // id is null, JPA inserts
    }
    public Product updateProduct(Long id, Product updatedFields) {
        Product existing = getProductById(id); // throws if not found
        existing.setName(updatedFields.getName());
        existing.setStore(updatedFields.getStore());
        existing.setPrice(updatedFields.getPrice());
        existing.setLastChecked(updatedFields.getLastChecked());
        return productRepository.save(existing); // id exists, JPA updates
    }
    public void deleteProduct(Long id) {
        Product existing = getProductById(id); // throws if not found
        productRepository.delete(existing);
    }
}
