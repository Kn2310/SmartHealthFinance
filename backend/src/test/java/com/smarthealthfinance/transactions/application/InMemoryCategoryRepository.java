package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.smarthealthfinance.transactions.TransactionsFixtures.DINING_OUT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.GROCERIES;
import static com.smarthealthfinance.transactions.TransactionsFixtures.RETIRED;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SALARY;

/** Recorte do catálogo, com uma categoria inativa, na ordem de exibição. */
public final class InMemoryCategoryRepository implements CategoryRepository {

    private final List<Category> categories;

    public InMemoryCategoryRepository() {
        this(List.of(SALARY, RETIRED, DINING_OUT, GROCERIES));
    }

    public InMemoryCategoryRepository(List<Category> categories) {
        this.categories = categories;
    }

    @Override
    public List<Category> findAll() {
        return categories.stream().sorted(Comparator.comparingInt(Category::displayOrder)).toList();
    }

    @Override
    public Optional<Category> findByCode(CategoryCode code) {
        return categories.stream().filter(category -> category.code().equals(code)).findFirst();
    }
}
