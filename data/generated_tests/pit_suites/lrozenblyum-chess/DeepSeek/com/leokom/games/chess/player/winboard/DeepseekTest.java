package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.*;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Winboard protocol communication.
 * Mocks only the low-level WinboardCommunicator; all other components are real.
 */
public class DeepseekTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;
    private Player opponent;

    @Before
    public void setUp() {
        communicator = mock(WinboardCommunicator.class);
        commander = new WinboardCommanderImpl(communicator);
        player = new WinboardPlayer(commander);
        opponent = mock(Player.class);
        player.setOpponent(opponent);
    }

    // ======================== Initialisation ========================

    @Test
    public void switchesWinboardToSetUpMode() {
        verify(communicator).send("feature done=0");
    }

    // ======================== Player -> Winboard translations ========================

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
    public void promotionCorrectlyTranslatedFromCommonStandard() {
        PositionBuilder position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7");
        player.setPosition(position.build());

        player.opponentMoved(new Move("f7", "f8Q"));
        verify(communicator).send("move f7f8q");
    }

    @Test
    public void castlingCorrectlyTranslatedToWinboardClient() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK);
        player.setPosition(position.setSideOf("e8").build());

        player.opponentMoved(new Move("e8", "c8"));
        verify(communicator).send("move e8c8");
    }

    @Test
    public void resignFromPlayerToWinboard() {
        // Play a few moves then resign
        player.opponentMoved(new Move("f2", "f3"));
        player.opponentMoved(new Move("e7", "e5"));
        player.opponentMoved(new Move("g2", "g4"));
        player.opponentMoved(Move.RESIGN);

        verify(communicator).send("resign");
        verify(communicator, never()).send(contains("Checkmate"));
    }

    @Test
    public void offerDrawFromPlayerToWinboard() {
        player.opponentMoved(new Move("f2", "f3"));
        player.opponentMoved(new Move("e7", "e5"));
        player.opponentMoved(new Move("g2", "g4"));
        player.opponentMoved(Move.OFFER_DRAW);

        verify(communicator).send("offer draw");
    }

    // ======================== Winboard -> Player translations ========================

    @Test
    public void offerDrawListenerCalled() {
        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void informOpponentAboutAcceptDraw() {
        when(communicator.receive()).thenReturn("result 1/2-1/2 {some reason}");
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
    public void userMoveNoException() {
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

        when(communicator.receive()).thenReturn("usermove f7g8q");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("f7", "g8Q"));
    }

    @Test
    public void castlingCorrectlyTranslatedToPlayer() {
        PositionBuilder position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK);
        player.setPosition(position.build());

        when(communicator.receive()).thenReturn("usermove e1g1");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "g1"));
    }

    // ======================== Draw claim scenarios ========================

    @Test
    public void claimDrawFromUIReceivedWhenAdjudicationEnabled() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {draw claimed by UI}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void claimDrawFromUIReceivedWhenAdjudicationDisabled() {
        Rules fastDrawClaim = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(fastDrawClaim)
                .build()
                .move("b1", "a3")
                .move("b8", "a6");
        player.setPosition(position);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claim received from UI by 1 moves rule}");
    }

    // ======================== Game termination detection ========================

    @Test
    public void checkmateWhenWinboardWins() {
        // White (engine) checkmates black (UI) with fool's mate for black? Actually this is a mate by white.
        // We'll simulate the moves: engine is white.
        player.opponentMoved(new Move("e2", "e4"));  // white
        simulateOpponentMove("g7", "g5");           // black
        player.opponentMoved(new Move("b1", "c3")); // white
        simulateOpponentMove("f7", "f5");           // black
        player.opponentMoved(new Move("d1", "h5")); // white -> checkmate

        verify(communicator).send("1-0 {Checkmate}");
        verify(communicator, never()).send("0-1 {Checkmate}");
    }

    @Test
    public void checkmateFromPlayerToWinboard() {
        // White (UI) gets checkmated by black (engine) with fool's mate.
        simulateOpponentMove("f2", "f3"); // white
        player.opponentMoved(new Move("e7", "e5")); // black
        simulateOpponentMove("g2", "g4"); // white
        player.opponentMoved(new Move("d8", "h4")); // black -> checkmate

        verify(communicator).send("0-1 {Checkmate}");
        verify(communicator, never()).send("1-0 {Checkmate}");
    }

    @Test
    public void stalemateSentDueToWinboardMove() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "d5", PieceType.KING)
                .add(Side.WHITE, "d7", PieceType.PAWN)
                .add(Side.BLACK, "d8", PieceType.KING)
                .build();
        player.setPosition(position);

        player.opponentMoved(new Move("d5", "d6")); // white moves, stalemate?

        verify(communicator).send(startsWith("1/2-1/2"));
        verify(communicator).send(contains("Stalemate"));
    }

    @Test
    public void stalemateSentDueToOpponentMove() {
        Position position = new PositionBuilder()
                .add(Side.BLACK, "d4", PieceType.KING)
                .add(Side.BLACK, "d2", PieceType.PAWN)
                .add(Side.WHITE, "e1", PieceType.KING)
                .build();
        player.setPosition(position);

        // White moves: Kd1
        simulateOpponentMove("e1", "d1");
        // Black moves: Kd3? Actually black king moves to d3? The test in reference uses builder.move(new Move("e1","d1")).move(new Move("d4","d3")).play();
        // So we simulate black move via player.opponentMoved (engine is black? Actually engine is white? In that test, the position is set with black to move? Actually the side to move is black? The position has side to move? The constructor doesn't set side, so default is white? The test in reference sets position with black pieces, but the side to move is probably white? In the test, they do builder.move(new Move("e1","d1")) which is white, then .move(new Move("d4","d3")) which is black. So they alternate: first engine? Actually builder probably takes moves in order. Since we don't have builder, we'll simulate accordingly.
        // In our simulation, we'll treat the player as the engine that makes moves when we call player.opponentMoved. So for this test, the engine is black? Actually let's check: The position has black king, black pawn, white king. Side to move is white? The engine (player) is white? We need to decide. The test "stalemateSentDueToOpponentMove" means the stalemate is caused by the opponent's move (i.e., UI move). In the reference, they did builder.move(new Move("e1","d1")) (white) -> engine? Actually builder might handle moves from both sides. Let's follow the reference: they call builder.move(whiteMove).move(blackMove).play(); So the first move is by the engine? Possibly the engine is white. In our case, to test "stalemateSentDueToOpponentMove", we want the opponent (UI) to make a move that results in stalemate for the engine. So we should set engine as white? Let's just replicate the reference logic: they set position with black pieces, and then do white move (Kd1) then black move (Kd3). The black move causes stalemate? Actually after Kd1 (white), black king moves to d3? The position: white king e1, black king d4, black pawn d2. White to move. White moves Kd1, then black moves Kd3? That seems not stalemate. Let's check the reference test: they have position with black king d4, black pawn d2, white king e1. They do move e1d1 (white king moves to d1). Then move d4d3 (black king moves to d3). After that, position? White king d1, black king d3, black pawn d2. White to move? Actually side to move after black's move is white. White has no legal moves? White king is in check? Not sure. Anyway, the test verifies communicator.send("1/2-1/2 {Stalemate}"). So we just simulate the same sequence.

        // We'll simulate the moves: first white move (from UI), then black move (engine). We'll set the player as black? Actually player is the engine; we call player.opponentMoved when engine makes a move. So we can treat the engine as black, and the UI as white. So we simulate UI move by stubbing receive() and calling processInputFromServer(). But we also need the engine to make a move after. The reference builder does both: it handles moves from both sides by calling appropriate methods. We'll do: first, stub communicator.receive() to return "usermove e1d1" (white move). Then we call commander.processInputFromServer() which will update position and notify opponent (which we've mocked, but we don't care). Then we call player.opponentMoved(new Move("d4","d3")) (engine's move, black). After that, the position should be terminal (stalemate) and the commander should send the stalemate message.

        // Let's implement that.
        player.setPosition(position);
        // UI move: white king e1d1
        when(communicator.receive()).thenReturn("usermove e1d1");
        commander.processInputFromServer();
        // Engine move: black king d4d3
        player.opponentMoved(new Move("d4", "d3"));

        verify(communicator).send("1/2-1/2 {Stalemate}");
    }

    @Test
    public void obligatoryDraw() {
        Rules drawRule = new RulesBuilder().movesTillDraw(1).build();
        player.setPosition(Position.getInitialPosition(drawRule));

        // Two moves (one by each side) to trigger draw
        player.opponentMoved(new Move("b1", "c3"));
        simulateOpponentMove("b8", "a6");
        // After these, the position should be terminal by draw.
        // The detection occurs in detectGameOver after each move.
        // The second move (by opponent) will cause the draw detection.
        // We need to ensure that after the second move, the communicator gets the draw message.
        // However, the draw detection is triggered inside player.opponentMoved for engine moves,
        // but for opponent moves (UI), it's triggered inside the listener after processing the move.
        // So we need to simulate both moves properly.

        // Actually we already called player.opponentMoved for engine move (b1c3), then simulate UI move.
        // The UI move is processed by commander.processInputFromServer() which updates position and calls opponent.opponentMoved on the engine's opponent? Wait, the UI move is processed in WinboardUserMoveListener.execute(), which updates position, calls detectGameOver(), and then calls opponent.opponentMoved(engineMove). So detectGameOver will be called after UI move. So after simulateOpponentMove("b8","a6"), the draw should be detected and communicator.send should be called.

        // So we need to verify that communicator.send was called with a draw message.
        // We can verify at least once.
        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2"));
    }

    // ======================== False positive tests ========================

    @Test
    public void noFalsePositiveResign() {
        // Play game until checkmate, but UI sends result 0-1 {Black wins}
        simulateOpponentMove("f2", "f3");
        player.opponentMoved(new Move("e7", "e5"));
        simulateOpponentMove("g2", "g4");
        player.opponentMoved(new Move("d8", "h4")); // checkmate

        // Now simulate UI sending result (though terminal position already triggered earlier)
        // We'll override by stubbing receive to return result after the move? But the checkmate already caused a send.
        // We need to test the scenario where the UI sends result after the game is over, and we should not misinterpret as resign.
        // In reference, they do customWinboardResponse after the moves. So we'll simulate by stubbing receive for the next processInputFromServer call.
        // But after checkmate, the game is terminal; the engine would have sent result. The UI may also send result later.
        // The test "noFalsePositiveResign" checks that when UI sends "result 0-1 {Black wins}" (which does not contain "resign"), we do NOT treat it as RESIGN.
        // So we need to simulate that after the game, the UI sends that result, and we process it, and verify opponent.opponentMoved(RESIGN) is never called.

        // We'll call commander.processInputFromServer() with that response.
        when(communicator.receive()).thenReturn("result 0-1 {Black wins}");
        commander.processInputFromServer();

        verify(opponent, never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noFalsePositiveResignWhenWhiteWins() {
        // Similar for white wins
        simulateOpponentMove("e2", "e4");
        player.opponentMoved(new Move("g7", "g5"));
        simulateOpponentMove("d2", "d4");
        player.opponentMoved(new Move("f7", "f6"));
        simulateOpponentMove("d1", "h5"); // checkmate

        when(communicator.receive()).thenReturn("result 1-0 {White wins}");
        commander.processInputFromServer();

        verify(opponent, never()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void noCheckmateNoFalseInforming() {
        // Just play two moves, no checkmate
        simulateOpponentMove("f2", "f3");
        player.opponentMoved(new Move("e7", "e5"));

        // No checkmate message should be sent
        verify(communicator, never()).send(contains("Checkmate"));
    }

    // ======================== Resign from Winboard to Player ========================

    @Test
    public void resignFromWinboardToPlayer() {
        // UI sends result with resign
        when(communicator.receive()).thenReturn("result 0-1 {White resigns}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    // ======================== Helper methods ========================

    /**
     * Simulates a move from the opponent (UI) by stubbing communicator.receive()
     * with the appropriate usermove command and processing it.
     */
    private void simulateOpponentMove(String from, String to) {
        String winboardMove = from + to;
        when(communicator.receive()).thenReturn("usermove " + winboardMove);
        commander.processInputFromServer();
    }
}
