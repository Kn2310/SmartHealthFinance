package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.dto.ImportPageViews;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Histórico de importações do Workspace, mais recentes primeiro (offset, spec 05.6). */
@Service
public class ListImports {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final ImportAccess access;
    private final ImportBatchRepository batches;

    public ListImports(ImportAccess access, ImportBatchRepository batches) {
        this.access = access;
        this.batches = batches;
    }

    @Transactional(readOnly = true)
    public ImportPageViews.BatchPage execute(UUID workspaceId, Integer page, Integer pageSize) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        int resolvedPage = Paging.page(page);
        int resolvedSize = Paging.pageSize(pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        ImportBatchRepository.ImportBatchPage result = batches.search(workspace, resolvedPage, resolvedSize);
        // O histórico não calcula "mesmo arquivo já importado": é informação do preview.
        return new ImportPageViews.BatchPage(result.items().stream()
                .map(batch -> ImportBatchView.from(batch, false)).toList(), resolvedPage, resolvedSize,
                result.totalItems());
    }

    /** Validação de paginação compartilhada pelas listagens de importação. */
    static final class Paging {

        private Paging() {
        }

        static int page(Integer page) {
            int value = page == null ? 0 : page;
            if (value < 0) {
                throw new InvalidValueException("page", "OUT_OF_RANGE");
            }
            return value;
        }

        static int pageSize(Integer pageSize, int defaultSize, int maxSize) {
            int value = pageSize == null ? defaultSize : pageSize;
            if (value < 1 || value > maxSize) {
                throw new InvalidValueException("pageSize", "OUT_OF_RANGE");
            }
            return value;
        }
    }
}
