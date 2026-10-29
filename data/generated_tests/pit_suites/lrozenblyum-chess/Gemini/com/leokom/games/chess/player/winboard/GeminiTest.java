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
import static org.mockito.Mockito.*;

public class GeminiTest {

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
    public void shouldSendFeatureDoneZeroWhenHandlingInitialization() {
        verify(communicator).send("feature done=0");
    }

    @Test
    public void shouldPropagateDrawOfferToOpponentWhenDrawCommandReceived() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.OFFER_DRAW);
    }

    @Test
    public void shouldSendResignCommandWhenEngineResigns() {
        player.opponentMoved(Move.RESIGN);
        verify(communicator).send("resign");
    }

    @Test
    public void shouldSendOfferDrawCommandWhenEngineAcceptsDraw() {
        player.opponentMoved(Move.ACCEPT_DRAW);
        verify(communicator).send("offer draw");
    }

    @Test
    public void shouldPropagateAcceptDrawToOpponentWhenDrawResultReceived() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw agreed}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.ACCEPT_DRAW);
    }

    @Test
    public void shouldPropagateResignToOpponentWhenResignResultReceived() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1-0 {Black resigns}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.RESIGN);
    }

    @Test
    public void shouldPropagateClaimDrawWhenAdjudicationEnabledAndLegal() {
        Rules rules = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(rules)
                .build()
                .move("g1", "f3")
                .move("g8", "f6");
        player.setPosition(position);

        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("result 1/2-1/2 {Draw claimed}");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
    }

    @Test
    public void shouldPropagateClaimDrawAndInformUIWhenAdjudicationDisabledAndLegal() {
        Rules rules = new RulesBuilder().movesTillClaimDraw(1).build();
        Position position = new PositionBuilder()
                .initial()
                .rules(rules)
                .build()
                .move("b1", "a3")
                .move("b8", "a6");
        player.setPosition(position);

        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("draw");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(Move.CLAIM_DRAW);
        verify(communicator).send("1/2-1/2 {Draw claim received from UI by 1 moves rule}");
    }

    @Test
    public void shouldUpdateSideToBlackWhenLegalUserMoveReceived() {
        player.setOpponent(mock(Player.class));

        when(communicator.receive()).thenReturn("usermove e2e4");
        commander.processInputFromServer();

        assertEquals(Side.BLACK, player.getPosition().getSideToMove());
    }

    @Test
    public void shouldTranslateInboundPromotionCommandToMoveProperly() {
        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .add(Side.BLACK, "g8", PieceType.QUEEN)
                .setSideOf("f7")
                .build();
        player.setPosition(position);

        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("usermove f7g8q");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("f7", "g8Q"));
    }

    @Test
    public void shouldTranslateOutboundPromotionMoveToCommandProperly() {
        Position position = new PositionBuilder()
                .addPawn(Side.WHITE, "f7")
                .setSideOf("f7")
                .build();
        player.setPosition(position);

        player.opponentMoved(new Move("f7", "f8Q"));
        verify(communicator).send("move f7f8q");
    }

    @Test
    public void shouldTranslateInboundCastlingCommandToMoveProperly() {
        Position position = new PositionBuilder()
                .add(Side.WHITE, "e1", PieceType.KING)
                .add(Side.WHITE, "h1", PieceType.ROOK)
                .build();
        player.setPosition(position);

        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        when(communicator.receive()).thenReturn("usermove e1g1");
        commander.processInputFromServer();

        verify(opponent).opponentMoved(new Move("e1", "g1"));
    }

    @Test
    public void shouldTranslateOutboundCastlingMoveToCommandProperly() {
        Position position = new PositionBuilder()
                .add(Side.BLACK, "e8", PieceType.KING)
                .add(Side.BLACK, "a8", PieceType.ROOK)
                .setSideOf("e8")
                .build();
        player.setPosition(position);

        player.opponentMoved(new Move("e8", "c8"));
        verify(communicator).send("move e8c8");
    }

    @Test
    public void shouldSendCheckmateForWhiteWhenWinboardDeliversMate() {
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
    public void shouldSendObligatoryDrawWhenDrawRuleTriggers() {
        player.setPosition(Position.getInitialPosition(new RulesBuilder().movesTillDraw(1).build()));

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("b1", "c3"))
                .move(new Move("b8", "a6"))
                .play();

        verify(communicator, atLeastOnce()).send(startsWith("1/2-1/2"));
    }

    @Test
    public void shouldSendStalemateWhenWinboardMoveCausesStalemate() {
        Position position = new PositionBuilder()
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
    public void shouldSendStalemateWhenEngineMoveCausesStalemate() {
        Position position = new PositionBuilder()
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
    public void shouldSendCheckmateForBlackWhenEngineDeliversMate() {
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
    public void shouldSendResignWhenEngineResigns() {
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
    public void shouldSendOfferDrawWhenEngineOffersDraw() {
        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(new Move("g2", "g4"))
                .move(Move.OFFER_DRAW)
                .play();

        verify(communicator).send("offer draw");
    }

    @Test
    public void shouldPropagateResignationToOpponentWhenWinboardResigns() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder(player, communicator);
        builder.move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .move(Move.RESIGN)
                .play();

        verify(builder.getOpponent()).opponentMoved(Move.RESIGN);
    }

    @Test
    public void shouldNotMisclassifyBlackWinsAsResignation() {
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
    public void shouldNotMisclassifyWhiteWinsAsResignation() {
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
    public void shouldNotSendCheckmateWhenGameIsInProgress() {
        Player opponent = mock(Player.class);
        player.setOpponent(opponent);

        new WinboardTestGameBuilder(player, communicator)
                .move(new Move("f2", "f3"))
                .move(new Move("e7", "e5"))
                .play();

        verify(communicator, never()).send(contains("Checkmate"));
    }
}