package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.nio.NioChatServer;
import com.nexusconnect.servicebackend.security.AuthenticatedUser;
import com.nexusconnect.servicebackend.web.dto.game.TicTacToeMoveRequest;
import com.nexusconnect.servicebackend.web.dto.game.TicTacToeStartRequest;
import com.nexusconnect.servicebackend.web.dto.game.TicTacToeStateDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tictactoe")
public class TicTacToeController {

    private final NioChatServer nioChatServer;

    public TicTacToeController(NioChatServer nioChatServer) {
        this.nioChatServer = nioChatServer;
    }

    @PostMapping("/start")
    public ResponseEntity<TicTacToeStateDto> startGame(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody TicTacToeStartRequest request) {
        requirePrincipal(principal);
        try {
            var snapshot = nioChatServer.startTicTacToe(principal.username(), request.opponent());
            return ResponseEntity.ok(TicTacToeStateDto.fromSnapshot(snapshot));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/current")
    public ResponseEntity<?> currentGame(@AuthenticationPrincipal AuthenticatedUser principal) {
        requirePrincipal(principal);
        return nioChatServer.currentTicTacToeFor(principal.username())
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.ok(TicTacToeStateDto.fromSnapshot(snapshot)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/move/{gameId}")
    public ResponseEntity<TicTacToeStateDto> makeMove(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @PathVariable long gameId,
                                                      @Valid @RequestBody TicTacToeMoveRequest request) {
        requirePrincipal(principal);
        try {
            var snapshot = nioChatServer.makeTicTacToeMove(gameId, principal.username(), request.row(), request.col());
            return ResponseEntity.ok(TicTacToeStateDto.fromSnapshot(snapshot));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/resign/{gameId}")
    public ResponseEntity<TicTacToeStateDto> resign(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable long gameId) {
        requirePrincipal(principal);
        try {
            var snapshot = nioChatServer.resignTicTacToe(gameId, principal.username());
            return ResponseEntity.ok(TicTacToeStateDto.fromSnapshot(snapshot));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private void requirePrincipal(AuthenticatedUser principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }
}
