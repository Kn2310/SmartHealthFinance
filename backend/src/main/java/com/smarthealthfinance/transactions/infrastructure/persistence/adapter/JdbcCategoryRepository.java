package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Category;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Catálogo do sistema (ADR-0012), gravado pela migration V9. Só leitura. */
@Repository
public class JdbcCategoryRepository implements CategoryRepository {

    private static final String SELECT = "select code, nature, name, display_order, active from categories";

    private static final RowMapper<Category> CATEGORY = (rs, rowNum) -> new Category(
            new CategoryCode(rs.getString("code")),
            CategoryNature.valueOf(rs.getString("nature")),
            rs.getString("name"),
            rs.getInt("display_order"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public JdbcCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Category> findAll() {
        return jdbc.query(SELECT + " order by display_order", CATEGORY);
    }

    @Override
    public Optional<Category> findByCode(CategoryCode code) {
        return jdbc.query(SELECT + " where code = ?", CATEGORY, code.value()).stream().findFirst();
    }
}
