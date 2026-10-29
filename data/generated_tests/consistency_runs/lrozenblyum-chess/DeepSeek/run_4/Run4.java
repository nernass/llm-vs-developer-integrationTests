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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

/**
 * Integration tests for WinboardPlayer with real WinboardCommanderImpl
 * and mocked WinboardCommunicator transport layer.
 * 
 * Following the pattern from WinBoardPlayerIntegrationTest but with
 * additional coverage for edge cases and protocol scenarios.
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

    @Test
    public void switchesWinboardToSetUpMode() {
        verify(communicator).send("feature done=0");
    }

    @Test
    public void offerDrawListenerCalled() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void informWinboardAboutResign() {
        assertTranslationOfCommandFromPlayerToWinboardClient(Move.RESIGN, "resign");
    }

    @Test
    public void informWinboardAboutAcceptDraw() {
        assertTranslationOfCommandFromPlayerToWinboardClient(Move.ACCEPT_DRAW, "offer draw");
    }

    @Test
    public void informOpponentAboutAcceptDraw() {
        assertTranslationOfReceivedCommandToMoveForOpponent(
                "result 1/2-1/2 {some maybe impl. specific message}", Move.ACCEPT_DRAW);
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
    public void claimDrawFromUIReceivedWhenAdjudicationEnabled() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDrawPossibility = new PositionBuilder()
                .initial()
                .rules(fastDrawClaimPossibility)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(positionWithClaimDrawPossibility);

        assertTranslationOfReceivedCommandToMoveForOpponent(
                "result 1/2-1/2 {draw claimed by UI - this string is not part of specification}",
                Move.CLAIM_DRAW);
    }

    @Test
    public void claimDrawFromUIReceivedWhenAdjudicationDisabled() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw(1).build();
        Position positionWithClaimDrawPossibility = new PositionBuilder()
                .initial()
                .rules(fastDrawClaimPossibility)
                .build()
                .move("b1", "a3")
                .move("b8", "a6");
        player.setPosition(positionWithClaimDrawPossibility);

        assertTranslationOfReceivedCommandToMoveForOpponent("draw", Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claim received from UI by 1 moves rule}");
    }

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
    public void promotionCorrectlyTranslatedFromCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7");

        player.setPosition(position.build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("f7", "f8Q"), "move f7f8q");
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
    public void castlingCorrectlyTranslatedToWinboardClient() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK);

        player.setPosition(position.setSideOf("e8").build());

        assertTranslationOfCommandFromPlayerToWinboardClient(
                new Move("e8", "c8"), "move e8c8");
    }

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
    public void obligatoryDraw() {
        player.setPosition(Position.getInitialPosition(new RulesBuilder().movesTillDraw(1).build()));

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("b1", "c3"))
                .move(new Move("b8", "a6"))
                .play();

        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2"));
    }

    @Test
    public void stalemateSentDueToWinboardMove() {
        final Position position = new PositionBuilder()
                .add(Side.WHITE, "d5", PieceType.KING)
                .add(Side.WHITE, "d7", PieceType.PAWN)
                .add(Side.BLACK, "d8", PieceType.KING)
                .build();

        player.setPosition(position);
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("d5", "d6"))
                .play();

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
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("e1", "d1"))
                .move(new Move("d4", "d3"))
                .play();

        verify(communicator).send("1/2-1/2 {Stalemate}");
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

    @Test
    public void noCheckmateNoFalseInforming() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .play();

        verify(communicator, never()).send("0-1 {LeokomChess : checkmate}");
    }

    @Test
    public void illegalMoveRejected() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position with white to move, but we send illegal move
        when(communicator.receive()).thenReturn("usermove e2e5"); // pawn cannot move to e5
        commander.processInputFromServer();

        verify(communicator).send("Illegal move: e2e5");
        // Opponent should not be notified of illegal move
        verify(opponent, never()).opponentMoved(any(Move.class));
    }

    @Test
    public void xboardCommandReceived() {
        when(communicator.receive()).thenReturn("xboard");
        commander.processInputFromServer();
        // No exception should be thrown, and the listener should handle it
        // This is verified by the fact that processInputFromServer completes without error
    }

    @Test
    public void quitCommandReceived() {
        when(communicator.receive()).thenReturn("quit");
        commander.processInputFromServer();
        assertTrue(player.needShuttingDown());
    }

    @Test
    public void forceCommandReceived() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("force");
        commander.processInputFromServer();

        verify(opponent).switchToRecodingMode();
    }

    @Test
    public void goCommandReceived() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("go");
        commander.processInputFromServer();

        verify(opponent).joinGameForSideToMove();
    }

    @Test
    public void newGameCommandReceived() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Set up a non-initial position to verify it gets reset
        Position nonInitial = new PositionBuilder()
                .add(Side.WHITE, "e2", PieceType.PAWN)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(nonInitial);

        when(communicator.receive()).thenReturn("new");
        commander.processInputFromServer();

        assertEquals(Position.getInitialPosition(), player.getPosition());
        verify(opponent).leaveRecordingMode();
        verify(opponent).opponentSuggestsMeStartNewGameBlack();
    }

    @Test
    public void protoverCommandReceived() {
        when(communicator.receive()).thenReturn("protover 2");
        commander.processInputFromServer();

        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    @Test
    public void gameOverWithCheckmateFromWinboard() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.WHITE, "d1", PieceType.QUEEN)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 0-1 {Black wins}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void gameOverWithDrawFromWinboard() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.BLACK, "e8", PieceType.KING)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw}");
        commander.processInputFromServer();

        // Position with only kings is stalemate, but the result message should handle it
        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void opponentMovedWithMultipleMoves() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        player.opponentMoved(new Move("e2", "e4"), new Move("e7", "e5"));

        Position expected = Position.getInitialPosition()
                .move("e2", "e4")
                .move("e7", "e5");
        assertEquals(expected.getSideToMove(), player.getPosition().getSideToMove());
    }

    @Test
    public void opponentMovedWithResignAndDrawSequence() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        player.opponentMoved(Move.RESIGN);
        verify(communicator).send("resign");

        // Reset communicator for second verification
        prepare();
        player.setOpponent(opponent);

        player.opponentMoved(Move.OFFER_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void acceptDrawWhenWaiting() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Create position where accept draw is possible
        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);
        // Set waiting for accept draw state
        position.setWaitingForAcceptDraw(true);

        player.opponentMoved(Move.ACCEPT_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void claimDrawWhenAvailable() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(fastDrawClaimPossibility)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        player.opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claimed by 1 moves rule}");
    }

    @Test
    public void normalMoveWhenNotSpecial() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        Move move = new Move("e2", "e4");
        player.opponentMoved(move);

        verify(communicator).send("move e2e4");
        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    @Test
    public void multipleMovesReceivedFromWinboard() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive())
                .thenReturn("usermove e2e4")
                .thenReturn("usermove e7e5")
                .thenReturn("quit");

        // Process first move
        commander.processInputFromServer();
        assertEquals(Side.BLACK, player.getPosition().getSideToMove());

        // Process second move
        commander.processInputFromServer();
        assertEquals(Side.WHITE, player.getPosition().getSideToMove());
    }

    @Test
    public void commandWithParameterProcessing() {
        when(communicator.receive()).thenReturn("protover 2");
        commander.processInputFromServer();

        verify(communicator).send("feature usermove=1");
        verify(communicator).send("feature done=1");
    }

    @Test
    public void unknownCommandIgnored() {
        when(communicator.receive()).thenReturn("unknown_command");
        commander.processInputFromServer();
        // No exception should be thrown, command should be ignored
    }

    @Test
    public void nullReceiveHandled() {
        when(communicator.receive()).thenReturn(null);
        commander.processInputFromServer();
        // No exception should be thrown
    }

    @Test
    public void drawOfferFromUIWhenAdjudicationDisabled() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Position where draw claim is NOT possible
        Position position = new PositionBuilder()
                .initial()
                .build();
        player.setPosition(position);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        // Should be treated as OFFER_DRAW, not CLAIM_DRAW
        verify(opponent).opponentMoved(Move.OFFER_DRAW);
        // UI should NOT be informed about draw claim
        verify(communicator, never()).send(startsWith("1/2-1/2"));
    }

    @Test
    public void acceptDrawFromUIWhenWaiting() {
        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        Position position = new PositionBuilder()
                .initial()
                .build();
        position.setWaitingForAcceptDraw(true);
        player.setPosition(position);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void gameOverWithCheckmateFromPlayerSide() {
        // Position where white is in checkmate
        Position position = new PositionBuilder()
                .add(Side.WHITE, "h8", PieceType.KING)
                .add(Side.BLACK, "h7", PieceType.QUEEN)
                .add(Side.BLACK, "g7", PieceType.KING)
                .setSide(Side.WHITE)
                .build();
        player.setPosition(position);

        final Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        // Simulate white making a move that results in checkmate (but white is already in checkmate)
        // Actually we need to simulate the situation where opponent's move causes checkmate
        // For test purposes, we'll use the builder
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(new Move("d8", "h4"))
                .play();

        verify(communicator).send("0-1 {Checkmate}");
    }

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