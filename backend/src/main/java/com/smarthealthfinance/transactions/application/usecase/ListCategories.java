package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.dto.CategoryView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Catálogo de categorias disponível no Workspace (ADR-0012 §6): hoje, o catálogo do sistema, inclusive as inativas,
 * na ordem de exibição. A autorização é a mesma das demais rotas do Workspace.
 */
@Service
public class ListCategories {

    private final TransactionAccess access;
    private final CategoryRepository categories;

    public ListCategories(TransactionAccess access, CategoryRepository categories) {
        this.access = access;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> execute(UUID workspaceId) {
        access.requireWorkspace(workspaceId);
        return categories.findAll().stream().map(CategoryView::from).toList();
    }
}
