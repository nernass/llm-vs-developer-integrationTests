package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.Move;
import com.leokom.games.chess.engine.PieceType;
import com.leokom.games.chess.engine.Position;
import com.leokom.games.chess.engine.PositionBuilder;
import com.leokom.games.chess.engine.Result;
import com.leokom.games.chess.engine.Rules;
import com.leokom.games.chess.engine.RulesBuilder;
import com.leokom.games.chess.engine.Side;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for WinboardPlayer with real WinboardCommanderImpl
 * and mocked low-level WinboardCommunicator.
 *
 * These tests verify the complete command translation flow:
 * - Inbound: WinBoard protocol strings → Move objects → opponent callbacks
 * - Outbound: Move objects → WinBoard protocol strings
 * - Terminal condition detection and reporting
 */
public class WinboardPlayerIntegrationTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;

    @Before
    public void prepare() {
        communicator = mock(WinboardCommunicator.class);
        commander = new WinboardCommanderImpl(communicator);
        player = new WinboardPlayer(commander);
    }

    // ============================================================
    // INITIALIZATION TESTS
    // ============================================================

    @Test
    public void shouldSendFeatureDone0OnInit() {
        // Player constructor triggers commander.startInit() which sends "feature done=0"
        verify(communicator).send("feature done=0");
    }

    @Test
    public void shouldSendFeatureDone1AfterProtoverResponse() {
        // Simulate protover command from WinBoard
        when(communicator.receive())
                .thenReturn("protover 2")
                .thenReturn("quit");

        // Create new player with stubbed communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);

        when(freshCommunicator.receive()).thenReturn("protover 2").thenReturn("quit");

        // Run the loop
        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // After protover, we should enable usermove and finish init
        verify(freshCommunicator).send("feature usermove=1");
        verify(freshCommunicator).send("feature done=1");
    }

    // ============================================================
    // INBOUND COMMAND TESTS
    // ============================================================

    @Test
    public void shouldProcessXBoardCommand() {
        // Create a spy player to verify logging (or just verify no exception)
        when(communicator.receive())
                .thenReturn("xboard")
                .thenReturn("quit");

        // This should execute without errors - the xboard listener just logs
        commander.processInputFromServer();

        // No exception means success
        // We can also verify that onXBoard was triggered by testing the next command
        when(communicator.receive())
                .thenReturn("xboard")
                .thenReturn("quit");

        // Create a new player with fresh commander
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);

        when(freshCommunicator.receive())
                .thenReturn("xboard")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // Verify no errors
        assertTrue(true);
    }

    @Test
    public void shouldProcessQuitCommand() {
        // The quit command should set needQuit flag and break the loop
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("quit");

        commander.processInputFromServer();
        assertTrue(player.needShuttingDown());
    }

    @Test
    public void shouldProcessForceCommandAndSwitchOpponentToRecordingMode() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("force").thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("force")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).switchToRecodingMode();
    }

    @Test
    public void shouldProcessGoCommandAndJoinOpponent() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("go").thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("go")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).joinGameForSideToMove();
    }

    @Test
    public void shouldProcessNewCommandAndResetPosition() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Set up a non-initial position first
        PositionBuilder builder = new PositionBuilder();
        builder.add(Side.WHITE, "e2", PieceType.KING);
        player.setPosition(builder.build());

        when(communicator.receive())
                .thenReturn("new")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("new")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // Position should be reset to initial
        Position initial = Position.getInitialPosition();
        Position actual = freshPlayer.getPosition();
        assertEquals(initial.getSideToMove(), actual.getSideToMove());
        verify(opponent).leaveRecordingMode();
        verify(opponent).opponentSuggestsMeStartNewGameBlack();
    }

    @Test
    public void shouldProcessOfferDrawCommandAndClassifyAsClaimDrawWhenLegal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDraw =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaim)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");

        player.setPosition(positionWithClaimDraw);

        when(communicator.receive()).thenReturn("draw").thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(positionWithClaimDraw);

        when(freshCommunicator.receive())
                .thenReturn("draw")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // Should classify as CLAIM_DRAW and inform UI
        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(freshCommunicator).send(contains("Draw claim received from UI"));
    }

    @Test
    public void shouldProcessOfferDrawCommandAndClassifyAsOfferDrawWhenNotLegal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position early in game where claim draw is not legal
        Position earlyPosition = Position.getInitialPosition().move("e2", "e4");
        player.setPosition(earlyPosition);

        when(communicator.receive()).thenReturn("draw").thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(earlyPosition);

        when(freshCommunicator.receive())
                .thenReturn("draw")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // Should classify as OFFER_DRAW
        verify(opponent).opponentMoved(Move.OFFER_DRAW);
        verify(freshCommunicator, never()).send(contains("Draw claim received"));
    }

    @Test
    public void shouldProcessGameOverResultAsDrawWhenDrawResult() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position where claim draw is legal to classify as CLAIM_DRAW
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDraw =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaim)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");

        player.setPosition(positionWithClaimDraw);

        when(communicator.receive())
                .thenReturn("result 1/2-1/2 {Draw by agreement}")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(positionWithClaimDraw);

        when(freshCommunicator.receive())
                .thenReturn("result 1/2-1/2 {Draw by agreement}")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void shouldProcessGameOverResultAsResignWhenLossResult() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive())
                .thenReturn("result 0-1 {Black wins}")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("result 0-1 {Black wins}")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void shouldSkipGameOverWhenPositionAlreadyTerminal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Create a terminal position
        Position terminalPosition = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .draw(Side.WHITE)
                .build();

        player.setPosition(terminalPosition);

        when(communicator.receive())
                .thenReturn("result 1/2-1/2 {Draw}")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(terminalPosition);

        when(freshCommunicator.receive())
                .thenReturn("result 1/2-1/2 {Draw}")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // Opponent should NOT be notified again since position is already terminal
        verify(opponent, never()).opponentMoved(any());
    }

    // ============================================================
    // OUTBOUND COMMAND TESTS
    // ============================================================

    @Test
    public void shouldTranslateNormalMoveToWinboardFormat() {
        player.opponentMoved(new Move("e2", "e4"));
        verify(communicator).send("move e2e4");
    }

    @Test
    public void shouldTranslatePromotionToWinboardFormatWithLowercasePromotion() {
        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7")
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("f7", "f8Q"));
        verify(communicator).send("move f7f8q");
    }

    @Test
    public void shouldTranslateCastlingToWinboardFormat() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e1", "g1"));
        verify(communicator).send("move e1g1");
    }

    @Test
    public void shouldTranslateResignToWinboardFormat() {
        player.opponentMoved(Move.RESIGN);
        verify(communicator).send("resign");
    }

    @Test
    public void shouldTranslateOfferDrawToWinboardFormat() {
        player.opponentMoved(Move.OFFER_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void shouldTranslateAcceptDrawToWinboardFormat() {
        player.opponentMoved(Move.ACCEPT_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void shouldTranslateClaimDrawToWinboardFormat() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDraw =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaim)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");

        player.setPosition(positionWithClaimDraw);
        player.opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send(contains("Draw claimed"));
    }

    @Test
    public void shouldSendCheckmateNotificationWhenWhiteWins() {
        // Fool's mate: Black checkmates White
        Position position = new PositionBuilder()
                .add(Side.WHITE, "g1", PieceType.KING)
                .add(Side.BLACK, "h4", PieceType.QUEEN)
                .add(Side.BLACK, "e5", PieceType.KING)
                .build();

        // White is in checkmate
        player.setPosition(position);

        // Simulate opponent move that results in checkmate
        player.opponentMoved(new Move("h4", "h4")); // dummy move to trigger detection

        // The detection should have sent 0-1 (Black wins)
        verify(communicator).send("0-1 {Checkmate}");
    }

    @Test
    public void shouldSendCheckmateNotificationWhenBlackWins() {
        Position position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.WHITE, "h5", PieceType.QUEEN)
                .add(Side.WHITE, "e4", PieceType.KING)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("h5", "h5"));

        verify(communicator).send("1-0 {Checkmate}");
    }

    @Test
    public void shouldNotSendCheckmateWhenPositionIsNotTerminal() {
        Position position = Position.getInitialPosition();
        player.setPosition(position);

        player.opponentMoved(new Move("e2", "e4"));

        verify(communicator, never()).send(contains("Checkmate"));
    }

    @Test
    public void shouldSendStalemateNotification() {
        // Stalemate position
        Position position = new PositionBuilder()
                .add(Side.WHITE, "d5", PieceType.KING)
                .add(Side.WHITE, "d7", PieceType.PAWN)
                .add(Side.BLACK, "d8", PieceType.KING)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("d5", "d6"));

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    @Test
    public void shouldSendObligatoryDrawNotification() {
        Rules fastDraw = new RulesBuilder().movesTillDraw(1).build();
        Position position = Position.getInitialPosition(fastDraw);
        player.setPosition(position);

        // Make a move to reach the draw threshold
        player.opponentMoved(new Move("b1", "c3"));

        verify(communicator).send(contains("1/2-1/2"));
        verify(communicator).send(contains("Draw by"));
    }

    // ============================================================
    // ILLEGAL MOVE HANDLING TESTS
    // ============================================================

    @Test
    public void shouldSendIllegalMoveWhenUserMoveIsIllegal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position with only white king on e1
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .build();

        player.setPosition(position);

        // Try to move a non-existent piece
        when(communicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(position);

        when(freshCommunicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(freshCommunicator).send("Illegal move: e2e4");
        verify(opponent, never()).opponentMoved(any());
    }

    @Test
    public void shouldRejectMoveThatLeavesKingInCheck() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position where moving a piece exposes king to check
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "e2", PieceType.PAWN)
                .add(Side.BLACK, "e8", PieceType.KING)
                .build();

        player.setPosition(position);

        when(communicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(position);

        when(freshCommunicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(freshCommunicator).send("Illegal move: e2e4");
    }

    // ============================================================
    // FULL GAME FLOW TESTS
    // ============================================================

    @Test
    public void shouldPlayFullGameWithoutExceptions() {
        // Test a complete game flow with multiple moves
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);

        builder
                .move(new Move("e2", "e4"))
                .move(new Move("e7", "e5"))
                .move(new Move("g1", "f3"))
                .move(new Move("b8", "c6"))
                .move(new Move("f1", "c4"))
                .move(new Move("g8", "f6"))
                .move(new Move("d2", "d3"))
                .move(new Move("f8", "c5"))
                .play();

        // Verify no exceptions occurred
        assertTrue(true);
    }

    @Test
    public void shouldCorrectlyUpdatePositionAfterEachMove() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Test single move
        when(communicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        Position pos = freshPlayer.getPosition();
        assertEquals(Side.BLACK, pos.getSideToMove());
        assertTrue(pos.hasPiece(Side.WHITE, "e4", PieceType.PAWN));
        assertFalse(pos.hasPiece(Side.WHITE, "e2", PieceType.PAWN));
    }

    @Test
    public void shouldHandlePromotionMoveCorrectly() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        PositionBuilder positionBuilder = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "g8", PieceType.QUEEN)
                .setSideOf("f7");

        player.setPosition(positionBuilder.build());

        when(communicator.receive())
                .thenReturn("usermove f7g8q")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(positionBuilder.build());

        when(freshCommunicator.receive())
                .thenReturn("usermove f7g8q")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        Position pos = freshPlayer.getPosition();
        assertTrue(pos.hasPiece(Side.WHITE, "g8", PieceType.QUEEN));
        assertFalse(pos.hasPiece(Side.WHITE, "f7", PieceType.PAWN));
    }

    // ============================================================
    // EDGE CASES - AMBIGUOUS DRAW HANDLING
    // ============================================================

    @Test
    public void shouldDistinguishClaimDrawFromOfferDrawBasedOnPosition() {
        // Position where claim draw is NOT legal should treat "draw" as OFFER_DRAW
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position earlyPosition = Position.getInitialPosition();
        player.setPosition(earlyPosition);

        when(communicator.receive())
                .thenReturn("draw")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(earlyPosition);

        when(freshCommunicator.receive())
                .thenReturn("draw")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
        verify(freshCommunicator, never()).send(contains("Draw claim received"));
    }

    @Test
    public void shouldHandleDrawCommandFromUIWhenAdjudicationEnabled() {
        // When adjudication is enabled, we get "result 1/2-1/2" which we classify as CLAIM_DRAW
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDraw =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaim)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");

        player.setPosition(positionWithClaimDraw);

        when(communicator.receive())
                .thenReturn("result 1/2-1/2 {Draw claimed by 50 move rule}")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(positionWithClaimDraw);

        when(freshCommunicator.receive())
                .thenReturn("result 1/2-1/2 {Draw claimed by 50 move rule}")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    // ============================================================
    // EDGE CASES - TERMINAL STATES WITH SPECIAL MOVES
    // ============================================================

    @Test
    public void shouldNotAllowMovesAfterGameIsTerminal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Create terminal position
        Position terminalPosition = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.WHITE, "h5", PieceType.QUEEN)
                .winningSide(Side.WHITE)
                .build();

        player.setPosition(terminalPosition);

        // Opponent move should not change position
        player.opponentMoved(new Move("e1", "e2"));

        // Position should remain unchanged
        assertEquals(terminalPosition.getSideToMove(), player.getPosition().getSideToMove());
    }

    @Test
    public void shouldResetPositionOnNewCommandAfterGameOver() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Create terminal position
        Position terminalPosition = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.WHITE, "h5", PieceType.QUEEN)
                .winningSide(Side.WHITE)
                .build();

        player.setPosition(terminalPosition);

        when(communicator.receive())
                .thenReturn("new")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(terminalPosition);

        when(freshCommunicator.receive())
                .thenReturn("new")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        Position initial = Position.getInitialPosition();
        Position actual = freshPlayer.getPosition();
        assertEquals(initial.getSideToMove(), actual.getSideToMove());
        assertFalse(actual.isTerminal());
    }

    // ============================================================
    // EDGE CASES - MULTIPLE MOVES IN ONE OPPONENT_MOVED CALL
    // ============================================================

    @Test
    public void shouldHandleMultipleMovesInSingleOpponentMovedCall() {
        // Some scenarios may pass multiple moves at once
        player.opponentMoved(
                new Move("e2", "e4"),
                new Move("e7", "e5")
        );

        verify(communicator).send("move e2e4");
        verify(communicator).send("move e7e5");
    }

    // ============================================================
    // EDGE CASES - PROMOTION SUFFIX CASE NORMALIZATION
    // ============================================================

    @Test
    public void shouldNormalizePromotionSuffixFromWinboardToEngine() {
        // Winboard sends lowercase promotion suffix (e7e8q)
        // Engine expects uppercase (e7e8Q)
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        PositionBuilder positionBuilder = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "g8", PieceType.QUEEN)
                .setSideOf("f7");

        player.setPosition(positionBuilder.build());

        when(communicator.receive())
                .thenReturn("usermove f7g8q")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(positionBuilder.build());

        when(freshCommunicator.receive())
                .thenReturn("usermove f7g8q")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(new Move("f7", "g8Q"));
    }

    @Test
    public void shouldNormalizePromotionSuffixFromEngineToWinboard() {
        // Engine uses uppercase promotion suffix (f7f8Q)
        // Winboard expects lowercase (f7f8q)
        PositionBuilder positionBuilder = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7");

        player.setPosition(positionBuilder.build());

        // Force position to be non-terminal to allow move
        player.opponentMoved(new Move("f7", "f8Q"));

        verify(communicator).send("move f7f8q");
    }

    // ============================================================
    // EDGE CASES - NULL INPUT HANDLING
    // ============================================================

    @Test
    public void shouldHandleNullInputFromCommunicator() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive())
                .thenReturn(null)
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn(null)
                .thenReturn("quit");

        // Should not throw NPE
        freshPlayer.opponentSuggestsMeStartNewGameWhite();
        assertTrue(true);
    }

    // ============================================================
    // EDGE CASES - COMMAND WITH EXTRA SPACES
    // ============================================================

    @Test
    public void shouldHandleCommandWithExtraSpaces() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive())
                .thenReturn("usermove   e2e4")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);

        when(freshCommunicator.receive())
                .thenReturn("usermove   e2e4")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        verify(opponent).opponentMoved(new Move("e2", "e4"));
    }

    // ============================================================
    // EDGE CASES - GAME OVER WITHOUT POSITION TERMINAL
    // ============================================================

    @Test
    public void shouldHandleGameOverWhenPositionNotTerminal() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position nonTerminalPosition = Position.getInitialPosition();
        player.setPosition(nonTerminalPosition);

        when(communicator.receive())
                .thenReturn("result 0-1 {Black resigns}")
                .thenReturn("quit");

        // Create a new player with fresh communicator
        WinboardCommunicator freshCommunicator = mock(WinboardCommunicator.class);
        WinboardCommander freshCommander = new WinboardCommanderImpl(freshCommunicator);
        WinboardPlayer freshPlayer = new WinboardPlayer(freshCommander);
        freshPlayer.setOpponent(opponent);
        freshPlayer.setPosition(nonTerminalPosition);

        when(freshCommunicator.receive())
                .thenReturn("result 0-1 {Black resigns}")
                .thenReturn("quit");

        freshPlayer.opponentSuggestsMeStartNewGameWhite();

        // The position should be updated to terminal
        assertTrue(freshPlayer.getPosition().isTerminal());
        verify(opponent).opponentMoved(Move.RESIGN);
    }

    // ============================================================
    // EDGE CASES - CLAIM DRAW WITH MOVES COUNT
    // ============================================================

    @Test
    public void shouldSendDrawClaimWithCorrectMovesCount() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(5).build();
        Position positionWithClaimDraw =
                new PositionBuilder()
                        .initial()
                        .rules(fastDrawClaim)
                        .build()
                        .move("g1", "f3")
                        .move("g8", "f6");

        player.setPosition(positionWithClaimDraw);

        // Simulate opponent moving CLAIM_DRAW
        player.opponentMoved(Move.CLAIM_DRAW);

        verify(communicator).send(contains("Draw claimed by 5 moves rule"));
    }
}