package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.*;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for WinboardPlayer with real WinboardCommanderImpl
 * and mocked low-level WinboardCommunicator transport layer.
 *
 * These tests verify the complete translation chain:
 * 1. Winboard protocol inbound commands -> Move objects -> Position updates
 * 2. Move objects -> Winboard protocol outbound commands
 * 3. Game state detection and notification (checkmate, stalemate, draw, etc.)
 */
@RunWith(MockitoJUnitRunner.class)
public class WinboardPlayerIntegrationTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;
    private Player opponent;

    @Captor
    private ArgumentCaptor<String> commandCaptor;

    @Before
    public void setUp() {
        communicator = mock(WinboardCommunicator.class);
        commander = new WinboardCommanderImpl(communicator);
        player = new WinboardPlayer(commander);
        opponent = mock(Player.class);
        player.setOpponent(opponent);
    }

    // ============= INITIALIZATION TESTS =============

    @Test
    public void switchesWinboardToSetUpMode() {
        verify(communicator).send("feature done=0");
    }

    @Test
    public void enablesUserMovePrefixesOnProtover() {
        when(communicator.receive())
            .thenReturn("protover 2")
            .thenReturn("quit");

        commander.processInputFromServer();

        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    // ============= INBOUND COMMAND TRANSLATION TESTS =============

    @Test
    public void offerDrawListenerCalled() {
        when(communicator.receive()).thenReturn("draw");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void acceptDrawFromWinboardResult() {
        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw agreed}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void resignListenerCalled() {
        when(communicator.receive()).thenReturn("result 1-0 {Black resigns}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void resignListenerCalledWithWhiteResigns() {
        when(communicator.receive()).thenReturn("result 0-1 {White resigns}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void claimDrawFromUIWhenAdjudicationEnabled() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaim = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(positionWithClaim);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {draw claimed by UI}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void claimDrawFromUIWhenAdjudicationDisabled() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaim = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("b1", "a3")
                .move("b8", "a6");
        player.setPosition(positionWithClaim);

        when(communicator.receive()).thenReturn("draw");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claim received from UI by 1 moves rule}");
    }

    @Test
    public void userMoveNoException() {
        when(communicator.receive()).thenReturn("usermove e2e4");

        commander.processInputFromServer();

        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    @Test
    public void userMoveUpdatesPositionCorrectly() {
        when(communicator.receive()).thenReturn("usermove e2e4");

        commander.processInputFromServer();

        Position expectedPosition = Position.getInitialPosition().move("e2", "e4");
        assertEquals(expectedPosition.getSideToMove(), player.getPosition().getSideToMove());
        assertTrue(player.getPosition().hasPawn(Side.WHITE, "e4"));
        assertTrue(player.getPosition().isFree("e2"));
    }

    // ============= INBOUND PROMOTION TESTS =============

    @Test
    public void promotionCorrectlyTranslatedToCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "g8", PieceType.QUEEN)
                .setSideOf("f7");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove f7g8q");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("f7", "g8Q"));
        assertTrue(player.getPosition().hasQueen(Side.WHITE, "g8"));
        assertTrue(player.getPosition().isFree("f7"));
    }

    @Test
    public void promotionToKnightTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e7")
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSideOf("e7");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove e7e8n");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e7", "e8N"));
        assertEquals(PieceType.KNIGHT, player.getPosition().getPieceType("e8"));
    }

    @Test
    public void promotionToRookTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "d7")
                .add(Side.BLACK, "d8", PieceType.KING)
                .setSideOf("d7");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove d7d8r");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("d7", "d8R"));
        assertEquals(PieceType.ROOK, player.getPosition().getPieceType("d8"));
    }

    @Test
    public void promotionToBishopTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "c7")
                .add(Side.BLACK, "c8", PieceType.KING)
                .setSideOf("c7");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove c7c8b");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("c7", "c8B"));
        assertEquals(PieceType.BISHOP, player.getPosition().getPieceType("c8"));
    }

    // ============= INBOUND CASTLING TESTS =============

    @Test
    public void castlingWhiteKingSideTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .setSideOf("e1");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove e1g1");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "g1"));
        assertTrue(player.getPosition().isFree("e1"));
        assertTrue(player.getPosition().isFree("h1"));
        assertTrue(player.getPosition().isOccupied("g1"));
        assertTrue(player.getPosition().isOccupied("f1"));
    }

    @Test
    public void castlingWhiteQueenSideTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "a1", PieceType.ROOK)
                .setSideOf("e1");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove e1c1");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "c1"));
        assertTrue(player.getPosition().isFree("e1"));
        assertTrue(player.getPosition().isFree("a1"));
        assertTrue(player.getPosition().isOccupied("c1"));
        assertTrue(player.getPosition().isOccupied("d1"));
    }

    @Test
    public void castlingBlackKingSideTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "h8", PieceType.ROOK)
                .setSideOf("e8");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove e8g8");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e8", "g8"));
    }

    @Test
    public void castlingBlackQueenSideTranslatedCorrectly() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK)
                .setSideOf("e8");

        player.setPosition(position.build());
        when(communicator.receive()).thenReturn("usermove e8c8");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e8", "c8"));
    }

    // ============= INBOUND ILLEGAL MOVE TESTS =============

    @Test
    public void illegalMoveSendsErrorToWinboard() {
        when(communicator.receive()).thenReturn("usermove e2e5");

        commander.processInputFromServer();

        verify(communicator).send("Illegal move: e2e5");
        // Position should not change
        assertEquals(Side.WHITE, player.getPosition().getSideToMove());
    }

    // ============= OUTBOUND COMMAND TRANSLATION TESTS =============

    @Test
    public void informWinboardAboutResign() {
        player.opponentMoved(Move.RESIGN);
        verify(communicator).send("resign");
    }

    @Test
    public void informWinboardAboutAcceptDraw() {
        player.opponentMoved(Move.ACCEPT_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void informWinboardAboutOfferDraw() {
        player.opponentMoved(Move.OFFER_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void promotionCorrectlyTranslatedFromCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7");

        player.setPosition(position.build());
        player.opponentMoved(new Move("f7", "f8Q"));

        verify(communicator).send("move f7f8q");
    }

    @Test
    public void promotionToKnightTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e7")
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSideOf("e7");

        player.setPosition(position.build());
        player.opponentMoved(new Move("e7", "e8N"));

        verify(communicator).send("move e7e8n");
    }

    @Test
    public void promotionToRookTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "d7")
                .add(Side.BLACK, "d8", PieceType.KING)
                .setSideOf("d7");

        player.setPosition(position.build());
        player.opponentMoved(new Move("d7", "d8R"));

        verify(communicator).send("move d7d8r");
    }

    @Test
    public void castlingWhiteKingSideTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .setSideOf("e1");

        player.setPosition(position.build());
        player.opponentMoved(new Move("e1", "g1"));

        verify(communicator).send("move e1g1");
    }

    @Test
    public void castlingWhiteQueenSideTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "a1", PieceType.ROOK)
                .setSideOf("e1");

        player.setPosition(position.build());
        player.opponentMoved(new Move("e1", "c1"));

        verify(communicator).send("move e1c1");
    }

    @Test
    public void castlingBlackKingSideTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "h8", PieceType.ROOK)
                .setSideOf("e8");

        player.setPosition(position.build());
        player.opponentMoved(new Move("e8", "g8"));

        verify(communicator).send("move e8g8");
    }

    @Test
    public void castlingBlackQueenSideTranslatedToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK)
                .setSideOf("e8");

        player.setPosition(position.build());
        player.opponentMoved(new Move("e8", "c8"));

        verify(communicator).send("move e8c8");
    }

    // ============= GAME OVER DETECTION TESTS =============

    @Test
    public void checkmateWhenWinboardWins() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("e2", "e4"))
                .move(new Move("g7", "g5"))
                .move(new Move("b1", "c3"))
                .move(new Move("f7", "f5"))
                .move(new Move("d1", "h5"))
                .play();

        verify(communicator).send("1-0 {Checkmate}");
        verify(communicator, never()).send("0-1 {Checkmate}");
    }

    @Test
    public void checkmateFromPlayerToWinboard() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(new Move("d8", "h4"))
                .play();

        verify(communicator).send("0-1 {Checkmate}");
        verify(communicator, never()).send("1-0 {Checkmate}");
    }

    @Test
    public void checkmateAfterPlayerMoveNotifiesWinboard() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "h6", PieceType.QUEEN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "g8", PieceType.PAWN)
                .add(Side.BLACK, "h7", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("h6", "h8"));

        verify(communicator).send("1-0 {Checkmate}");
    }

    @Test
    public void stalemateSentDueToWinboardMove() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "d5", PieceType.KING)
                .add(Side.WHITE, "d7", PieceType.PAWN)
                .add(Side.BLACK, "d8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("d5", "d6"))
                .play();

        verify(communicator).send(contains("1/2-1/2"));
        verify(communicator).send(contains("Stalemate"));
    }

    @Test
    public void stalemateSentDueToOpponentMove() {
        Position position = new PositionBuilder()
                .add(Side.BLACK, "d4", PieceType.KING)
                .add(Side.BLACK, "d2", PieceType.PAWN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("e1", "d1"))
                .move(new Move("d4", "d3"))
                .play();

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    @Test
    public void obligatoryDrawDetected() {
        player.setPosition(Position.getInitialPosition(new RulesBuilder().movesTillDraw(1).build()));

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("b1", "c3"))
                .move(new Move("b8", "a6"))
                .play();

        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2"));
    }

    @Test
    public void obligatoryDrawMessageContainsMoveCount() {
        int movesTillDraw = 3;
        player.setPosition(Position.getInitialPosition(
                new RulesBuilder().movesTillDraw(movesTillDraw).build()));

        // Need to make enough moves without captures or pawn moves
        // Using knight moves which don't trigger capture or pawn moves
        PositionBuilder pb = new PositionBuilder().initial()
                .rules(new RulesBuilder().movesTillDraw(1).build());
        Position pos = pb.build();
        pos = pos.move("b1", "c3");
        pos = pos.move("g8", "f6");
        player.setPosition(pos);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("c3", "b1"))
                .move(new Move("f6", "g8"))
                .play();

        verify(communicator).send(startsWith("1/2-1/2"));
        verify(communicator).send(contains("Draw"));
    }

    @Test
    public void resignFromPlayerToWinboard() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(Move.RESIGN)
                .play();

        verify(communicator).send("resign");
        verify(communicator, never()).send(contains("Checkmate"));
    }

    @Test
    public void offerDrawFromPlayerToWinboard() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(Move.OFFER_DRAW)
                .play();

        verify(communicator).send("offer draw");
    }

    @Test
    public void resignFromWinboardToPlayer() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder.move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(Move.RESIGN)
                .play();

        verify(builder.getOpponent()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void claimDrawFromPlayerToWinboard() {
        Position position = new PositionBuilder()
                .initial()
                .rules(new RulesBuilder().movesTillClaimDraw(1).build())
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        // Need to simulate a position where claim draw is legal
        // Using the position from the test where claim draw is legal
        player.opponentMoved(Move.CLAIM_DRAW);

        verify(communicator).send(contains("1/2-1/2"));
        verify(communicator).send(contains("Draw claimed"));
    }

    @Test
    public void opponentNotifiedAboutClaimDrawFromWinboard() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaim = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(positionWithClaim);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {draw claimed}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    // ============= NEGATIVE TESTS (No false positives) =============

    @Test
    public void noFalsePositiveResign() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder.move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(new Move("d8", "h4"))
                .customWinboardResponse("result 0-1 {Black wins}")
                .play();

        verify(builder.getOpponent(), never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noFalsePositiveResignWhenWhiteWins() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder.move(new Move("e2", "e4"))
                .move(new Move("g7", "g5"))
                .move(new Move("d2", "d4"))
                .move(new Move("f7", "f6"))
                .move(new Move("d1", "h5"))
                .customWinboardResponse("result 1-0 {White wins}")
                .play();

        verify(builder.getOpponent(), never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noCheckmateNoFalseInforming() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .play();

        verify(communicator, never()).send(contains("Checkmate"));
    }

    @Test
    public void noCheckmateOnNonTerminalPositionAfterOpponentMove() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e2")
                .add(Side.BLACK, "e7", PieceType.PAWN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE);

        player.setPosition(position.build());
        player.opponentMoved(new Move("e2", "e4"));

        verify(communicator, never()).send(contains("Checkmate"));
        verify(communicator, never()).send(contains("1/2-1/2"));
    }

    // ============= EDGE CASE TESTS =============

    @Test
    public void ambiguousDrawCommandClassifiedAsOfferDrawWhenNotClaimable() {
        // Position where claim draw is not legal
        Position position = Position.getInitialPosition();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("draw");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
        verify(communicator, never()).send(contains("Draw claim"));
    }

    @Test
    public void ambiguousDrawResultClassifiedAsAcceptDrawWhenNotClaimable() {
        Position position = Position.getInitialPosition();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw}");

        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void gameOverResultFromWinboardDoesNotOverwriteKnownTerminalState() {
        // Create a terminal position that the engine already knows about
        Position terminalPosition = new PositionBuilder()
                .add(Side.WHITE, "h6", PieceType.QUEEN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "g8", PieceType.PAWN)
                .add(Side.BLACK, "h7", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        // This position should be checkmate
        player.setPosition(terminalPosition);

        // Winboard sends result after the fact
        when(communicator.receive()).thenReturn("result 1-0 {Checkmate}");

        // Should not throw and should not try to process as a move
        commander.processInputFromServer();
    }

    @Test
    public void quitCommandStopsMainLoop() {
        when(communicator.receive()).thenReturn("quit");

        // Use the run method which should exit cleanly
        player.opponentSuggestsMeStartNewGameWhite();

        assertTrue(player.needShuttingDown());
    }

    @Test
    public void forceCommandDelegatesToOpponent() {
        when(communicator.receive()).thenReturn("force");

        commander.processInputFromServer();

        verify(opponent).switchToRecodingMode();
    }

    @Test
    public void goCommandDelegatesToOpponent() {
        when(communicator.receive()).thenReturn("go");

        commander.processInputFromServer();

        verify(opponent).joinGameForSideToMove();
    }

    @Test
    public void newCommandResetsPositionAndOpponent() {
        when(communicator.receive()).thenReturn("new");

        // First make some move to change position
        player.setPosition(Position.getInitialPosition().move("e2", "e4"));

        commander.processInputFromServer();

        // Position should be reset to initial
        assertEquals(Position.getInitialPosition().getSideToMove(),
                player.getPosition().getSideToMove());

        // Opponent should be notified
        verify(opponent).leaveRecordingMode();
        verify(opponent).opponentSuggestsMeStartNewGameBlack();
    }

    @Test
    public void xboardCommandLoggedButNoSideEffects() {
        when(communicator.receive()).thenReturn("xboard");

        // Should not throw any exception
        commander.processInputFromServer();
    }

    @Test
    public void enPassantCaptureFromWinboard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e5")
                .addPawn(Side.BLACK, "d7")
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE);

        player.setPosition(position.build());

        // Winboard sends the move
        when(communicator.receive()).thenReturn("usermove e5d6");

        commander.processInputFromServer();

        // The black pawn on d5 should be captured (en passant)
        assertTrue(player.getPosition().isFree("d5"));
        assertTrue(player.getPosition().isOccupied("d6"));
        assertEquals(PieceType.PAWN, player.getPosition().getPieceType("d6"));
    }

    @Test
    public void enPassantCaptureToWinboard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e5")
                .addPawn(Side.BLACK, "d7")
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE);

        player.setPosition(position.build());
        player.opponentMoved(new Move("e5", "d6"));

        // Should send en passant capture notation
        verify(communicator).send("move e5d6");
    }
}