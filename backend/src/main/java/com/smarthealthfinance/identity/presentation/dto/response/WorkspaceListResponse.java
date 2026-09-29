package com.smarthealthfinance.identity.presentation.dto.response;

import java.util.List;

/** Envelope para permitir paginação futura sem quebrar o contrato (specs 05.6). */
public record WorkspaceListResponse(List<WorkspaceResponse> items) {
}
