package com.smarthealthfinance.goals.presentation.handler;

import com.smarthealthfinance.goals.application.exception.GoalDestinationInUseException;
import com.smarthealthfinance.goals.application.exception.GoalNotFoundException;
import com.smarthealthfinance.goals.application.exception.ReserveGoalAlreadyExistsException;
import com.smarthealthfinance.goals.domain.exception.GoalStatusConflictException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.error.ApiErrors;
import com.smarthealthfinance.shared.presentation.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GoalsExceptionHandler {

    @ExceptionHandler(GoalNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return respond(ErrorCode.GOAL_NOT_FOUND);
    }

    @ExceptionHandler(GoalStatusConflictException.class)
    ResponseEntity<ApiError> statusConflict() {
        return respond(ErrorCode.GOAL_STATUS_CONFLICT);
    }

    @ExceptionHandler(ReserveGoalAlreadyExistsException.class)
    ResponseEntity<ApiError> reserveExists() {
        return respond(ErrorCode.GOAL_RESERVE_ALREADY_EXISTS);
    }

    @ExceptionHandler(GoalDestinationInUseException.class)
    ResponseEntity<ApiError> destinationInUse() {
        return respond(ErrorCode.GOAL_DESTINATION_IN_USE);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code));
    }
}
