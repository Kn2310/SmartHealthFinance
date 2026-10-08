package com.smarthealthfinance.ingestion.application.dto;

import java.util.List;

/** Páginas por offset (spec 05.6). */
public final class ImportPageViews {

    private ImportPageViews() {
    }

    public record BatchPage(List<ImportBatchView> items, int page, int pageSize, long totalItems) {

        public BatchPage {
            items = List.copyOf(items);
        }
    }

    public record RecordPage(List<ImportRecordView> items, int page, int pageSize, long totalItems) {

        public RecordPage {
            items = List.copyOf(items);
        }
    }
}
