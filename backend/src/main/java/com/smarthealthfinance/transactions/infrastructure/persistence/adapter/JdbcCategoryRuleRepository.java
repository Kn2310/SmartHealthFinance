package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcCategoryRuleRepository implements CategoryRuleRepository {

    private final JdbcTemplate jdbc;

    public JdbcCategoryRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LearnedRule> findAll(WorkspaceId workspaceId) {
        return jdbc.query("select merchant_key, category_code, category_nature from category_rules"
                        + " where workspace_id = ?",
                (rs, row) -> new LearnedRule(rs.getString("merchant_key"), new TransactionCategory(
                        new CategoryCode(rs.getString("category_code")),
                        CategoryNature.valueOf(rs.getString("category_nature")))),
                workspaceId.value());
    }

    @Override
    public void upsert(WorkspaceId workspaceId, LearnedRule rule, Instant now) {
        jdbc.update("""
                        insert into category_rules (workspace_id, merchant_key, category_code, category_nature,
                                                    created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (workspace_id, merchant_key, category_nature)
                        do update set category_code = excluded.category_code, updated_at = excluded.updated_at
                        """,
                workspaceId.value(), rule.merchantKey(), rule.category().code().value(),
                rule.category().nature().name(), Timestamp.from(now), Timestamp.from(now));
    }
}
