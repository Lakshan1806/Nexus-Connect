package com.nexusconnect.servicebackend.web.dto.game;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record TicTacToeMoveRequest(
        @Min(value = 0, message = "row must be between 0 and 2")
        @Max(value = 2, message = "row must be between 0 and 2")
        int row,
        @Min(value = 0, message = "col must be between 0 and 2")
        @Max(value = 2, message = "col must be between 0 and 2")
        int col
) {}
