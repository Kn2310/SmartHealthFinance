package com.smarthealthfinance.transactions.presentation.controller;

import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.transactions.application.usecase.ListCategories;
import com.smarthealthfinance.transactions.presentation.dto.response.CategoryListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/categories")
@Tag(name = "Categories")
public class CategoryController {

    private final ListCategories listCategories;

    public CategoryController(ListCategories listCategories) {
        this.listCategories = listCategories;
    }

    @GetMapping
    @Operation(summary = "Catálogo de categorias (do sistema), inclusive as inativas, na ordem de exibição")
    @ApiResponse(responseCode = "200", description = "Catálogo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CategoryListResponse list(@PathVariable UUID workspaceId) {
        return CategoryListResponse.from(listCategories.execute(workspaceId));
    }
}
