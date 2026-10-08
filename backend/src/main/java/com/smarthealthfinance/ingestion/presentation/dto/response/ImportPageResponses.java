package com.smarthealthfinance.ingestion.presentation.dto.response;

import com.smarthealthfinance.ingestion.application.dto.ImportPageViews;

import java.util.List;

/** Paginação por offset (spec 05.6). */
public final class ImportPageResponses {

    private ImportPageResponses() {
    }

    public record ImportPageResponse(List<ImportResponse> items, int page, int pageSize, long totalItems) {

        public static ImportPageResponse from(ImportPageViews.BatchPage view) {
            return new ImportPageResponse(view.items().stream().map(ImportResponse::from).toList(), view.page(),
                    view.pageSize(), view.totalItems());
        }
    }

    public record ImportRecordPageResponse(List<ImportRecordResponse> items, int page, int pageSize,
                                           long totalItems) {

        public static ImportRecordPageResponse from(ImportPageViews.RecordPage view) {
            return new ImportRecordPageResponse(view.items().stream().map(ImportRecordResponse::from).toList(),
                    view.page(), view.pageSize(), view.totalItems());
        }
    }
}
