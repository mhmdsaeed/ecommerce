package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.catalog.ProductImages;
import com.unispark.ecommerce.catalog.ProductImages.Added;
import com.unispark.ecommerce.catalog.ProductImages.Changed;
import com.unispark.ecommerce.catalog.ProductImages.ImageResult;
import com.unispark.ecommerce.catalog.ProductImages.LimitReached;
import com.unispark.ecommerce.catalog.ProductImages.NotFound;
import com.unispark.ecommerce.catalog.ProductImages.Rejected;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.shared.Htmx;

/** The images section of the product edit page: upload (several files at once), reorder, remove. */
@Controller
@RequestMapping("/admin/products/{productId}/images")
class ProductImageController {

    static final String SECTION = ProductController.EDIT_PAGE + " :: images";

    private final ProductImages images;
    private final Products products;

    ProductImageController(ProductImages images, Products products) {
        this.images = images;
        this.products = products;
    }

    /** One outcome line per file; the file name is shown escaped, as typed by the merchant's device. */
    record UploadOutcome(String fileName, String message, boolean ok) {
    }

    @PostMapping
    String upload(@PathVariable UUID productId, @RequestParam(name = "files", required = false) List<MultipartFile> files,
            Model model, HttpServletRequest request) throws IOException {
        var outcomes = new ArrayList<UploadOutcome>();
        for (var file : files == null ? List.<MultipartFile>of() : files) {
            if (file.isEmpty()) {
                continue;
            }
            ImageResult result;
            try (var input = file.getInputStream()) {
                result = images.add(productId, input);
            }
            var outcome = switch (result) {
                case Added added -> new UploadOutcome(file.getOriginalFilename(), "image.added", true);
                case Rejected(var reason) -> new UploadOutcome(file.getOriginalFilename(), "image." + reason.name(), false);
                case LimitReached limit -> new UploadOutcome(file.getOriginalFilename(), "image.limit", false);
                case NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                case Changed changed -> throw new IllegalStateException("add never reports Changed");
            };
            outcomes.add(outcome);
        }
        if (outcomes.isEmpty()) {
            outcomes.add(new UploadOutcome(null, "image.none-chosen", false));
        }
        model.addAttribute("uploadOutcomes", outcomes);
        return section(productId, model, request);
    }

    @PostMapping("/{imageId}/delete")
    String remove(@PathVariable UUID productId, @PathVariable UUID imageId, Model model, HttpServletRequest request) {
        requireFound(images.remove(productId, imageId));
        return section(productId, model, request);
    }

    @PostMapping("/{imageId}/move")
    String move(@PathVariable UUID productId, @PathVariable UUID imageId, @RequestParam int direction, Model model,
            HttpServletRequest request) {
        requireFound(images.move(productId, imageId, direction));
        return section(productId, model, request);
    }

    private String section(UUID productId, Model model, HttpServletRequest request) {
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/products/" + productId;
        }
        var product = products.find(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("product", product);
        model.addAttribute("maxImages", ProductImages.MAX_IMAGES);
        ProductController.refreshShelf(product, model);
        return SECTION;
    }

    private static void requireFound(ImageResult result) {
        if (result instanceof NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
