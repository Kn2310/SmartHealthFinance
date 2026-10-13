package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;

import java.util.List;
import java.util.Optional;

/** Catálogo do sistema (ADR-0012): só leitura, igual para todos os Workspaces. */
public interface CategoryRepository {

    /** Todas as categorias, inclusive as inativas, na ordem de exibição. */
    List<Category> findAll();

    Optional<Category> findByCode(CategoryCode code);
}
