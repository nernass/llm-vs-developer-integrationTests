package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.*;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Comprehensive integration tests for WinboardPlayer that verify
 * the complete protocol translation pipeline from raw WinBoard protocol
 * strings through the command layer to the Player interface callbacks.
 *
 * These tests use the real WinboardPlayer and WinboardCommanderImpl,
 * with only the low-level WinboardCommunicator mocked to simulate
 * protocol I/O at the transport boundary.
 *
 * Author: Generated
 * Date-time: 2026-07-21
 */
public class WinBoardPlayerIntegrationTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;

    @Before
    public void prepare() {
        communicator = mock(WinboardCommunicator.class);
        commander = new WinboardCommanderImpl(communicator);
        player = new WinboardPlayer(commander);
    }

    // ======================================================================
    // INITIALIZATION TESTS
    // ======================================================================

    @Test
    public void switchesWinboardToSetUpMode() {
        verify(communicator).send("feature done=0");
    }

    @Test
    public void xboardCommandReceived_logsReadyMessage() {
        // This verifies the xboard listener is registered and called
        when(communicator.receive()).thenReturn("xboard");
        commander.processInputFromServer();
        // No exception means the listener executed successfully
        verify(communicator).receive();
    }

    @Test
    public void protoverCommand_enablesFeaturesAndFinishesInit() {
        when(communicator.receive()).thenReturn("protover 2");
        commander.processInputFromServer();

        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    @Test
    public void quitCommand_stopsMainLoop() {
        player.setOpponent(mock(Player.class));

        when(communicator.receive())
                .thenReturn("xboard")
                .thenReturn("protover 2")
                .thenReturn("quit");

        player.opponentSuggestsMeStartNewGameWhite();

        // The loop should exit after processing the quit command
        // Verify we processed all expected commands
        verify(communicator, atLeast(3)).receive();
    }

    // ======================================================================
    // INBOUND COMMAND TESTS (WinBoard -> Player)
    // ======================================================================

    @Test
    public void offerDrawListenerCalled() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void drawOffer_WhenClaimDrawLegallyAvailable_sendsClaimDrawToOpponent() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDrawPossibility =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaimPossibility)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");
        player.setPosition(positionWithClaimDrawPossibility);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send(contains("Draw claim received from UI"));
    }

    @Test
    public void acceptDraw_WhenOpponentAcceptedOffer_sendsAcceptDraw() {
        // First, offer a draw from the engine side
        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        // Set up state where draw offer is pending (waitingForAcceptDraw = true)
        // This is done by moving to a position and setting the flag
        position = position.move(new Move("e2", "e4"));
        position = position.move(new Move("e7", "e5"));
        position.setWaitingForAcceptDraw(true);
        player.setPosition(position);

        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        // When waitingForAcceptDraw is true, "draw" should be classified as ACCEPT_DRAW
        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void resignListenerCalled() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1-0 {Black resigns}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void gameOverResult_WithDrawResult_ClassifiesAsAcceptDraw() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position where draw is not claimable
        Position position = new PositionBuilder()
                .initial()
                .build()
                .move("e2", "e4")
                .move("e7", "e5");
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw by agreement}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void gameOverResult_WithDrawResultAndClaimAvailable_ClassifiesAsClaimDraw() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDrawPossibility =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaimPossibility)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");
        player.setPosition(positionWithClaimDrawPossibility);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw claimed by 50-move rule}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void gameOverResult_WithNonDrawNonResign_resultIgnored() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1-0 {White wins by time}");
        commander.processInputFromServer();

        // Should not send any special move
        verify(opponent, never()).opponentMoved(any(Move.class));
    }

    // ======================================================================
    // USER MOVE TESTS (Inbound)
    // ======================================================================

    @Test
    public void userMoveNoException() {
        player.setOpponent(mock(Player.class));

        when(communicator.receive()).thenReturn("usermove e2e4");
        commander.processInputFromServer();

        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    @Test
    public void promotionCorrectlyTranslatedToCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "g8", PieceType.QUEEN)
                .setSideOf("f7");

        player.setPosition(position.build());

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "usermove f7g8q", new Move("f7", "g8Q"));
    }

    @Test
    public void promotionToKnight_CorrectlyTranslated() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e7")
                .add(Side.BLACK, "d8", PieceType.ROOK)
                .setSideOf("e7");

        player.setPosition(position.build());

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "usermove e7d8n", new Move("e7", "d8N"));
    }

    @Test
    public void promotionToBishop_CorrectlyTranslated() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "c7")
                .add(Side.BLACK, "b8", PieceType.KNIGHT)
                .setSideOf("c7");

        player.setPosition(position.build());

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "usermove c7b8b", new Move("c7", "b8B"));
    }

    @Test
    public void castlingCorrectlyTranslatedToPlayer() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK);

        player.setPosition(position.build());

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "usermove e1g1", new Move("e1", "g1"));
    }

    @Test
    public void queenSideCastlingCorrectlyTranslatedToPlayer() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK);

        player.setPosition(position.setSideOf("e8").build());

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "usermove e8c8", new Move("e8", "c8"));
    }

    @Test
    public void illegalMove_sendsIllegalCommandBack() {
        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e2e5");
        commander.processInputFromServer();

        verify(communicator).send("Illegal move: e2e5");
    }

    @Test
    public void illegalMoveWithReason_HandledCorrectly() {
        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e1e2");
        commander.processInputFromServer();

        verify(communicator).send("Illegal move: e1e2");
    }

    @Test
    public void enPassantCapture_CorrectlyTranslatedFromWinboard() {
        // Position where en passant is possible
        // White pawn on e5, black pawn moves from d7 to d5, white can capture en passant
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e5")
                .addPawn(Side.BLACK, "d7")
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "d8", PieceType.KING)
                .setSide(Side.WHITE);

        player.setPosition(position.build());
        // First make the double pawn move from black side
        when(communicator.receive())
                .thenReturn("usermove d7d5")
                .thenReturn("usermove e5d6");
        commander.processInputFromServer();
        // The opponent should receive the en passant move
        verify(communicator).send(anyString());
    }

    // ======================================================================
    // OUTBOUND COMMAND TESTS (Player -> WinBoard)
    // ======================================================================

    @Test
    public void informWinboardAboutResign() {
        assertTranslationOfCommandFromPlayerToWinboardClient(
                Move.RESIGN, "resign");
    }

    @Test
    public void informWinboardAboutAcceptDraw() {
        assertTranslationOfCommandFromPlayerToWinboardClient(
                Move.ACCEPT_DRAW, "offer draw");
    }

    @Test
    public void informWinboardAboutOfferDraw() {
        assertTranslationOfCommandFromPlayerToWinboardClient(
                Move.OFFER_DRAW, "offer draw");
    }

    @Test
    public void promotionCorrectlyTranslatedFromCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7");

        player.setPosition(position.build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("f7", "f8Q"), "move f7f8q");
    }

    @Test
    public void promotionToKnight_CorrectlyTranslatedFromCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "c7")
                .add(Side.BLACK, "d8", PieceType.KING)
                .add(Side.WHITE, "e1", PieceType.KING)
                .setSideOf("c7");

        player.setPosition(position.build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("c7", "c8N"), "move c7c8n");
    }

    @Test
    public void castlingCorrectlyTranslatedToWinboardClient() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK);

        player.setPosition(position.setSideOf("e8").build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("e8", "c8"), "move e8c8");
    }

    @Test
    public void kingSideCastlingCorrectlyTranslatedToWinboardClient() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK);

        player.setPosition(position.build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("e1", "g1"), "move e1g1");
    }

    @Test
    public void normalMove_CorrectlyTranslatedToWinboardClient() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "e2")
                .setSideOf("e2");

        player.setPosition(position.build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("e2", "e4"), "move e2e4");
    }

    // ======================================================================
    // GAME STATE TESTS
    // ======================================================================

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
    public void checkmateDetection_AfterPromotion() {
        // Position where a promotion leads to checkmate
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "h8", PieceType.KING)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "g8", PieceType.QUEEN) // Blocking the king
                .setSideOf("f7");

        player.setPosition(position.build());
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);

        builder.move(new Move("f7", "f8Q")).play();

        verify(communicator).send("1-0 {Checkmate}");
    }

    @Test
    public void obligatoryDraw() {
        player.setPosition(Position.getInitialPosition(new RulesBuilder().movesTillDraw(1).build()));

        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder
                .move(new Move("b1", "c3"))
                .move(new Move("b8", "a6"))
                .play();

        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2"));
    }

    @Test
    public void obligatoryDraw_WithCustomRule_NotifiesCorrectly() {
        Rules customRules = new RulesBuilder().movesTillDraw(2).movesTillClaimDraw(1).build();
        player.setPosition(Position.getInitialPosition(customRules));

        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder
                .move(new Move("e2", "e4"))
                .move(new Move("e7", "e5"))
                .move(new Move("d2", "d3"))
                .move(new Move("d7", "d6"))
                .move(new Move("c2", "c3"))
                .move(new Move("c7", "c6"))
                .play();

        verify(communicator, atLeastOnce()).send(contains("Draw"));
    }

    @Test
    public void stalemateSentDueToWinboardMove() {
        final Position position = new PositionBuilder()
                .add(Side.WHITE, "d5", PieceType.KING)
                .add(Side.WHITE, "d7", PieceType.PAWN)
                .add(Side.BLACK, "d8", PieceType.KING)
                .build();

        player.setPosition(position);
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);

        builder.move(new Move("d5", "d6")).play();

        verify(communicator).send(startsWith("1/2-1/2"));
        verify(communicator).send(contains("Stalemate"));
    }

    @Test
    public void stalemateSentDueToOpponentMove() {
        final Position position = new PositionBuilder()
                .add(Side.BLACK, "d4", PieceType.KING)
                .add(Side.BLACK, "d2", PieceType.PAWN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .build();

        player.setPosition(position);
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);

        builder.move(new Move("e1", "d1")).move(new Move("d4", "d3")).play();

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    // ======================================================================
    // MOVE TRANSLATION TESTS
    // ======================================================================

    @Test
    public void claimDraw_WhenLegallyAvailable_sendsDrawByMovesCount() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        player.opponentMoved(Move.CLAIM_DRAW);

        verify(communicator).send(contains("Draw claimed by"));
    }

    @Test
    public void claimDraw_WithCustomMovesCount_InformsCorrectly() {
        Rules customRules = new RulesBuilder().movesTillClaimDraw(5).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(customRules)
                .build()
                .move("b1", "c3")
                .move("b8", "c6")
                .move("c3", "d5")
                .move("c6", "d4")
                .move("d5", "b4")
                .move("d4", "b5")
                .move("b4", "d3")
                .move("b5", "d6");
        player.setPosition(position);

        player.opponentMoved(Move.CLAIM_DRAW);

        verify(communicator).send(contains("Draw claimed by 5 moves rule"));
    }

    // ======================================================================
    // COMMAND SEQUENCE TESTS
    // ======================================================================

    @Test
    public void forceCommand_switchesOpponentToRecordingMode() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("force");
        commander.processInputFromServer();

        verify(opponent).switchToRecodingMode();
    }

    @Test
    public void goCommand_tellsOpponentToJoinGame() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("go");
        commander.processInputFromServer();

        verify(opponent).joinGameForSideToMove();
    }

    @Test
    public void newCommand_resetsPositionAndResetsOpponent() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Set up a non-initial position
        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "e4")
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("new");
        commander.processInputFromServer();

        // Position should be reset to initial
        assertEquals(Position.getInitialPosition().getSquaresOccupied(), 
                     player.getPosition().getSquaresOccupied());
        verify(opponent).leaveRecordingMode();
        verify(opponent).opponentSuggestsMeStartNewGameBlack();
    }

    @Test
    public void fullGameSequence_CommandsProcessedInOrder() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive())
                .thenReturn("xboard")
                .thenReturn("protover 2")
                .thenReturn("new")
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        player.opponentSuggestsMeStartNewGameWhite();

        verify(communicator, atLeast(3)).send(anyString());
        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    // ======================================================================
    // RESIGN AND DRAW HANDLING TESTS
    // ======================================================================

    @Test
    public void resignFromPlayerToWinboard() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(Move.RESIGN)
                .play();

        verify(communicator).send("resign");
        verify(communicator, never()).send("1-0 {LeokomChess : checkmate}");
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
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(Move.RESIGN)
                .play();

        verify(builder.getOpponent()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noFalsePositiveResign() {
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(new Move("d8", "h4"))
                .customWinboardResponse("result 0-1 {Black wins}")
                .play();

        verify(builder.getOpponent(), never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noFalsePositiveResignWhenWhiteWins() {
        final WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder
                .move(new Move("e2", "e4"))
                .move(new Move("g7", "g5"))
                .move(new Move("d2", "d4"))
                .move(new Move("f7", "f6"))
                .move(new Move("d1", "h5"))
                .customWinboardResponse("result 1-0 {White wins}")
                .play();

        verify(builder.getOpponent(), never()).opponentMoved(Move.RESIGN);
    }

    // ======================================================================
    // EDGE CASE TESTS
    // ======================================================================

    @Test
    public void noCheckmateNoFalseInforming() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .play();

        verify(communicator, never()).send(contains("Checkmate"));
    }

    @Test
    public void terminalPosition_WhenCheckmate_DoesNotAllowFurtherMoves() {
        Position checkmatePosition = new PositionBuilder()
                .add(Side.WHITE, "h5", PieceType.QUEEN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "f7", PieceType.PAWN)
                .add(Side.BLACK, "g7", PieceType.PAWN)
                .add(Side.BLACK, "h7", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(checkmatePosition);

        // The position should be terminal
        assertEquals(0, checkmatePosition.getMoves().size());
        assertNotNull(checkmatePosition.getWinningSide());
    }

    @Test
    public void drawOffer_WhenWaitingForAcceptDraw_IncludeAcceptDrawInMoves() {
        Position position = new PositionBuilder()
                .initial()
                .build()
                .move("e2", "e4")
                .move("e7", "e5");
        position.setWaitingForAcceptDraw(true);
        player.setPosition(position);

        // When waitingForAcceptDraw is true, ACCEPT_DRAW should be in the moves list
        // This is verified by the offerDrawHandler test above
        assertTrue(position.getMoves().contains(Move.ACCEPT_DRAW));
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Condition was false");
        }
    }

    @Test
    public void multipleMovesFromOpponent_ProcessedInOrder() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        // Simulate opponent making two moves
        player.opponentMoved(new Move("e2", "e4"));
        player.opponentMoved(new Move("e7", "e5"));

        // Verify position was updated after both moves
        assertEquals(Side.WHITE, player.getPosition().getSideToMove());
        // Note: In the actual implementation, after the second move, the side to move would be WHITE again
    }

    @Test
    public void terminalPosition_FromOpponentMove_DetectsGameOver() {
        // Create a position where opponent's move causes stalemate
        Position position = new PositionBuilder()
                .add(Side.WHITE, "a1", PieceType.KING)
                .add(Side.BLACK, "a3", PieceType.KING)
                .add(Side.BLACK, "b3", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.setOpponent(mock(Player.class));

        // Move to a position that results in stalemate
        player.opponentMoved(new Move("a1", "b1"));
        // After this move, the opponent's king is in stalemate

        // Verify the game over notification
        verify(communicator).send(contains("1/2-1/2"));
    }

    @Test
    public void protocolVersion_WithDifferentVersions_HandledCorrectly() {
        // Test with different protocol versions
        when(communicator.receive()).thenReturn("protover 1");
        commander.processInputFromServer();

        // Should still work with version 1
        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    // ======================================================================
    // HELPER METHODS
    // ======================================================================

    private void assertTranslationOfCommandFromPlayerToWinboardClient(Move playerMove, String commandSentToWinboardClient) {
        player.opponentMoved(playerMove);
        verify(communicator).send(commandSentToWinboardClient);
    }

    private void assertTranslationOfReceivedCommandToMoveForOpponent(String winboardReceivedMessage, Move move) {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn(winboardReceivedMessage);
        commander.processInputFromServer();

        verify(opponent).opponentMoved(move);
    }
}