package com.smarthealthfinance.consents.presentation.dto.response;

import com.smarthealthfinance.consents.application.dto.ConsentView;

import java.util.List;

public record ConsentListResponse(List<ConsentResponse> items) {

    public static ConsentListResponse from(List<ConsentView> views) {
        return new ConsentListResponse(views.stream().map(ConsentResponse::from).toList());
    }
}
