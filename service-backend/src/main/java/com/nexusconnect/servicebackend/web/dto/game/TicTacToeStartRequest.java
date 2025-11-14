package com.nexusconnect.servicebackend.web.dto.game;

import jakarta.validation.constraints.NotBlank;

public record TicTacToeStartRequest(
        @NotBlank(message = "Opponent username is required")
        String opponent
) {}
