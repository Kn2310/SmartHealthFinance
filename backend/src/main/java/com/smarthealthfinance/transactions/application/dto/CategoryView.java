package com.smarthealthfinance.transactions.application.dto;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Category;

public record CategoryView(String code, String name, CategoryNature nature, boolean active) {

    public static CategoryView from(Category category) {
        return new CategoryView(category.code().value(), category.name(), category.nature(), category.active());
    }
}
