package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.Move;
import com.leokom.games.chess.engine.PieceType;
import com.leokom.games.chess.engine.Position;
import com.leokom.games.chess.engine.PositionBuilder;
import com.leokom.games.chess.engine.Rules;
import com.leokom.games.chess.engine.RulesBuilder;
import com.leokom.games.chess.engine.Side;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for WinboardPlayer verifying the full interaction between
 * WinboardPlayer, WinboardCommanderImpl, and WinboardCommunicator.
 * 
 * The low-level WinboardCommunicator is mocked to simulate WinBoard protocol messages,
 * while all other components (Player, Position, Move, Rules) are real implementations.
 */
public class WinBoardPlayerFullIntegrationTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;

    @Before
    public void prepare() {
        communicator = mock(WinboardCommunicator.class);
        commander = new WinboardCommanderImpl(communicator);
        player = new WinboardPlayer(commander);
    }

    // ==================== INITIALIZATION TESTS ====================

    @Test
    public void switchesWinboardToSetUpMode() {
        verify(communicator).send("feature done=0");
    }

    @Test
    public void switchesWinboardToFeatureModeOnXBoard() {
        when(communicator.receive()).thenReturn("xboard");
        commander.processInputFromServer();
        verify(communicator).send("feature done=0");
    }

    @Test
    public void enablesUserMovePrefixesOnProtover() {
        when(communicator.receive()).thenReturn("protover 2");
        commander.processInputFromServer();
        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    // ==================== COMMAND RECEPTION TESTS ====================

    @Test
    public void receivesOfferDrawFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void receivesGameOverDrawFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Set up a position where draw is legal
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw by agreement}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void receivesResignFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1-0 {Black resigns}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void receivesCheckmateFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position where black is in checkmate
        Position position = new PositionBuilder()
                .add(Side.WHITE, "h5", PieceType.QUEEN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "g8", PieceType.KNIGHT)
                .add(Side.BLACK, "f7", PieceType.PAWN)
                .add(Side.BLACK, "g7", PieceType.PAWN)
                .add(Side.BLACK, "h7", PieceType.PAWN)
                .setSide(Side.BLACK)
                .build();

        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1-0 {Checkmate}");
        commander.processInputFromServer();

        // The opponent should not be notified of a special move for checkmate
        verify(opponent, never()).opponentMoved(any(Move.class));
    }

    // ==================== COMMAND SENDING TESTS ====================

    @Test
    public void sendsResignToWinboard() {
        player.opponentMoved(Move.RESIGN);
        verify(communicator).send("resign");
    }

    @Test
    public void sendsOfferDrawToWinboard() {
        player.opponentMoved(Move.OFFER_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void sendsAcceptDrawToWinboard() {
        player.opponentMoved(Move.ACCEPT_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void sendsClaimDrawToWinboard() {
        PositionBuilder positionBuilder = new PositionBuilder();
        Position position = positionBuilder
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();

        // Set up rules for claim draw
        Rules rules = new RulesBuilder().movesTillClaimDraw(50).build();
        position = new PositionBuilder()
                .initial()
                .rules(rules)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");

        player.setPosition(position);
        player.opponentMoved(Move.CLAIM_DRAW);

        verify(communicator).send(startsWith("1/2-1/2 {Draw claimed by"));
    }

    @Test
    public void sendsNormalMoveToWinboard() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e2", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e2", "e4"));

        verify(communicator).send("move e2e4");
    }

    @Test
    public void sendsPromotionMoveToWinboardLowercase() {
        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "e7")
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e7", "e8Q"));

        verify(communicator).send("move e7e8q");
    }

    @Test
    public void sendsKingSideCastlingToWinboard() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e1", "g1"));

        verify(communicator).send("move e1g1");
    }

    @Test
    public void sendsQueenSideCastlingToWinboard() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "a1", PieceType.ROOK)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e1", "c1"));

        verify(communicator).send("move e1c1");
    }

    // ==================== RECEIVED MOVE TRANSLATION TESTS ====================

    @Test
    public void receivesNormalMoveFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e2", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e2e4");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e2", "e4"));
        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    @Test
    public void receivesPromotionMoveFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "e7")
                .add(Side.BLACK, "e8", PieceType.QUEEN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e7e8q");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e7", "e8Q"));
    }

    @Test
    public void receivesKingSideCastlingFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .add(Side.WHITE, "f1", PieceType.KNIGHT)
                .add(Side.WHITE, "g1", PieceType.KNIGHT)
                .setSide(Side.WHITE)
                .build();

        // Remove the blocking pieces
        position = position.move(new Move("g1", "h3"))
                .move(new Move("f1", "e2"));

        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e1g1");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "g1"));
    }

    @Test
    public void receivesQueenSideCastlingFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "a1", PieceType.ROOK)
                .add(Side.WHITE, "b1", PieceType.KNIGHT)
                .add(Side.WHITE, "c1", PieceType.KNIGHT)
                .add(Side.WHITE, "d1", PieceType.QUEEN)
                .setSide(Side.WHITE)
                .build();

        // Remove the blocking pieces
        position = position.move(new Move("b1", "a3"))
                .move(new Move("c1", "e2"))
                .move(new Move("d1", "e2"));

        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e1c1");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "c1"));
    }

    // ==================== GAME DETECTION TESTS ====================

    @Test
    public void detectsCheckmateWhenWhiteMatesBlack() {
        // Fool's mate: White gets checkmated
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
    public void detectsCheckmateWhenBlackMatesWhite() {
        // Scholar's mate: Black gets checkmated
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("e2", "e4"))
                .move(new Move("e7", "e5"))
                .move(new Move("d1", "h5"))
                .move(new Move("b8", "c6"))
                .move(new Move("f1", "c4"))
                .move(new Move("g8", "f6"))
                .move(new Move("h5", "f7"))
                .play();

        verify(communicator).send("1-0 {Checkmate}");
        verify(communicator, never()).send("0-1 {Checkmate}");
    }

    @Test
    public void detectsStalemateAfterWinboardMove() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h2", PieceType.PAWN)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("e1", "e2"))
                .play();

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    @Test
    public void detectsStalemateAfterEngineMove() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e2", PieceType.KING)
                .add(Side.BLACK, "e1", PieceType.KING)
                .add(Side.BLACK, "d2", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();

        player.setPosition(position);
        player.opponentMoved(new Move("e2", "d1"));

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    @Test
    public void detectsObligatoryDrawByMovesRule() {
        Rules rules = new RulesBuilder().movesTillDraw(1).build();
        Position position = Position.getInitialPosition(rules);
        player.setPosition(position);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("b1", "c3"))
                .move(new Move("b8", "a6"))
                .play();

        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2 {Draw by"));
    }

    // ==================== CLAIM DRAW TESTS ====================

    @Test
    public void claimDrawFromWinboardWhenAdjudicationEnabled() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules rules = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(rules)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw claimed by UI}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void claimDrawFromWinboardWhenAdjudicationDisabled() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Rules rules = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(rules)
                .build()
                .move("b1", "a3")
                .move("b8", "a6");
        player.setPosition(position);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claim received from UI by 1 moves rule}");
    }

    // ==================== ILLEGAL MOVE TESTS ====================

    @Test
    public void reportsIllegalMoveFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e2", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("usermove e2e5");
        commander.processInputFromServer();

        verify(communicator).send("Illegal move: e2e5");
        verify(opponent, never()).opponentMoved(any(Move.class));
    }

    // ==================== EDGE CASES ====================

    @Test
    public void noFalsePositiveResignOnBlackWin() {
        // Check that "result 0-1 {Black wins}" doesn't trigger a resignation notification
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Set up a non-terminal position
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 0-1 {Black wins}");
        commander.processInputFromServer();

        verify(opponent, never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noFalsePositiveResignOnWhiteWin() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1-0 {White wins}");
        commander.processInputFromServer();

        verify(opponent, never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void handlesNullReceiveResponse() {
        when(communicator.receive()).thenReturn(null);
        commander.processInputFromServer();
        // No exception should be thrown
    }

    @Test
    public void handlesUnknownWinboardCommand() {
        when(communicator.receive()).thenReturn("unknown_command");
        commander.processInputFromServer();
        // No exception should be thrown
    }

    @Test
    public void receivesQuitCommandAndStopsLoop() {
        when(communicator.receive())
                .thenReturn("quit");

        player.opponentSuggestsMeStartNewGameWhite();

        assertTrue(player.needShuttingDown());
    }

    @Test
    public void handlesForceCommandFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("force");
        commander.processInputFromServer();

        verify(opponent).switchToRecodingMode();
    }

    @Test
    public void handlesGoCommandFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("go");
        commander.processInputFromServer();

        verify(opponent).joinGameForSideToMove();
    }

    @Test
    public void handlesNewCommandFromWinboard() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("new");
        commander.processInputFromServer();

        verify(opponent).leaveRecordingMode();
        verify(opponent).opponentSuggestsMeStartNewGameBlack();
        assertEquals(Position.getInitialPosition(), player.getPosition());
    }

    // ==================== MOVE SEQUENCE TESTS ====================

    @Test
    public void handlesFullGameWithMultipleMoves() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Simulate a sequence of moves: 1. e4 e5 2. Nf3 Nc6
        when(communicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("usermove e7e5")
                .thenReturn("usermove g1f3")
                .thenReturn("usermove b8c6")
                .thenReturn("quit");

        player.opponentSuggestsMeStartNewGameWhite();

        // Verify that the position after the sequence is correct
        Position finalPosition = player.getPosition();
        assertTrue(finalPosition.hasPawn(Side.WHITE, "e4"));
        assertTrue(finalPosition.hasPawn(Side.BLACK, "e5"));
        assertTrue(finalPosition.hasPiece(Side.WHITE, "f3", PieceType.KNIGHT));
        assertTrue(finalPosition.hasPiece(Side.BLACK, "c6", PieceType.KNIGHT));
        assertEquals(Side.WHITE, finalPosition.getSideToMove());
    }
}