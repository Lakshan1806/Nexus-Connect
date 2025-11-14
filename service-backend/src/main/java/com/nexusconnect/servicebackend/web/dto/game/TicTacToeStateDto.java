package com.nexusconnect.servicebackend.web.dto.game;

import com.nexusconnect.servicebackend.nio.NioChatServer;

import java.util.ArrayList;
import java.util.List;

public record TicTacToeStateDto(
        long gameId,
        String playerX,
        String playerO,
        String currentTurn,
        String status,
        String winner,
        List<String> board,
        String lastMoveBy,
        Integer lastMoveRow,
        Integer lastMoveCol,
        long lastUpdated
) {
    public static TicTacToeStateDto fromSnapshot(NioChatServer.TicTacToeGameSnapshot snapshot) {
        List<String> boardRows = new ArrayList<>(3);
        char[][] board = snapshot.board();
        for (int r = 0; r < board.length; r++) {
            char[] row = board[r];
            StringBuilder builder = new StringBuilder(3);
            for (int c = 0; c < row.length; c++) {
                builder.append(row[c] == '\0' ? '-' : row[c]);
            }
            boardRows.add(builder.toString());
        }
        return new TicTacToeStateDto(
                snapshot.id(),
                snapshot.playerX(),
                snapshot.playerO(),
                snapshot.currentTurn(),
                snapshot.status(),
                snapshot.winner(),
                boardRows,
                snapshot.lastMoveBy(),
                snapshot.lastMoveRow(),
                snapshot.lastMoveCol(),
                snapshot.lastUpdated()
        );
    }
}
