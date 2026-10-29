
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
import org.mockito.InOrder;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class WinBoardPlayerProtocolIntegrationTest {

    private WinboardCommunicator communicator;
    private WinboardCommander commander;
    private WinboardPlayer player;

    @Before
    public void prepare() {
        communicator = mock( WinboardCommunicator.class );
        commander = new WinboardCommanderImpl( communicator );
        player = new WinboardPlayer( commander );
    }

    @Test
    public void shouldSwitchWinboardToSetupModeOnInitialization() {
        verify( communicator ).send( "feature done=0" );
    }

    @Test
    public void shouldCompleteProtoverHandshakeInCorrectOrder() {
        when( communicator.receive() ).thenReturn( "protover 2" );

        commander.processInputFromServer();

        InOrder inOrder = inOrder( communicator );
        inOrder.verify( communicator ).send( "feature done=0" );
        inOrder.verify( communicator ).send( "feature usermove=1" );
        inOrder.verify( communicator ).send( "feature done=1" );
    }

    @Test
    public void shouldTranslateInboundDrawCommandToDrawOfferWhenClaimIsUnavailable() {
        assertInboundCommandTranslatedToOpponentMove( "draw", Move.OFFER_DRAW );
    }

    @Test
    public void shouldTranslateOutboundResignToWinboardResignCommand() {
        assertOutboundCommandForPlayerMove( Move.RESIGN, "resign" );
    }

    @Test
    public void shouldTranslateOutboundAcceptedDrawToWinboardOfferDrawCommand() {
        assertOutboundCommandForPlayerMove( Move.ACCEPT_DRAW, "offer draw" );
    }

    @Test
    public void shouldTranslateInboundDrawResultToAcceptedDrawWhenClaimIsUnavailable() {
        assertInboundCommandTranslatedToOpponentMove(
                "result 1/2-1/2 {some maybe impl. specific message}",
                Move.ACCEPT_DRAW
        );
    }

    @Test
    public void shouldTranslateInboundResignResultToResignMove() {
        assertInboundCommandTranslatedToOpponentMove(
                "result 1-0 {Black resigns}",
                Move.RESIGN
        );
    }

    @Test
    public void shouldTranslateInboundDrawResultToClaimDrawWhenClaimIsLegal() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw( 1 ).build();
        Position positionWithClaimDrawPossibility = new PositionBuilder()
                .initial()
                .rules( fastDrawClaimPossibility )
                .build()
                .move( "g1", "f3" )
                .move( "g8", "f6" );
        player.setPosition( positionWithClaimDrawPossibility );

        assertInboundCommandTranslatedToOpponentMove(
                "result 1/2-1/2 {draw claimed by UI - this string is not part of specification}",
                Move.CLAIM_DRAW
        );
    }

    @Test
    public void shouldTranslateInboundDrawCommandToClaimDrawAndInformUiWhenAdjudicationDisabled() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw( 1 ).build();
        Position positionWithClaimDrawPossibility = new PositionBuilder()
                .initial()
                .rules( fastDrawClaimPossibility )
                .build()
                .move( "b1", "a3" )
                .move( "b8", "a6" );
        player.setPosition( positionWithClaimDrawPossibility );

        assertInboundCommandTranslatedToOpponentMove( "draw", Move.CLAIM_DRAW );
        verify( communicator ).send( "1/2-1/2 {Draw claim received from UI by 1 moves rule}" );
    }

    @Test
    public void shouldAcceptLegalUserMoveAndAdvanceSideToMove() {
        player.setOpponent( mock( Player.class ) );
        when( communicator.receive() ).thenReturn( "usermove e2e4" );

        commander.processInputFromServer();

        assertEquals( Side.BLACK, player.getPosition().getSideToMove() );
    }

    @Test
    public void shouldReportIllegalUserMoveAndNotNotifyOpponent() {
        Player opponent = mock( Player.class );
        player.setOpponent( opponent );
        when( communicator.receive() ).thenReturn( "usermove e2e5" );

        commander.processInputFromServer();

        verify( communicator ).send( "Illegal move: e2e5" );
        verify( opponent, never() ).opponentMoved( any() );
    }

    @Test
    public void shouldTranslateInboundPromotionToEngineMove() {
        PositionBuilder position = new PositionBuilder()
                .addPawn( Side.WHITE, "f7" )
                .add( Side.BLACK, "g8", PieceType.QUEEN )
                .setSideOf( "f7" );

        player.setPosition( position.build() );

        assertInboundCommandTranslatedToOpponentMove(
                "usermove f7g8q",
                new Move( "f7", "g8Q" )
        );
    }

    @Test
    public void shouldTranslateOutboundPromotionToWinboardLowercaseNotation() {
        PositionBuilder position = new PositionBuilder()
                .addPawn( Side.WHITE, "f7" )
                .setSideOf( "f7" );

        player.setPosition( position.build() );

        assertOutboundCommandForPlayerMove(
                new Move( "f7", "f8Q" ),
                "move f7f8q"
        );
    }

    @Test
    public void shouldTranslateInboundKingsideCastlingToEngineMove() {
        PositionBuilder position = new PositionBuilder()
                .add( Side.WHITE, "e1", PieceType.KING )
                .add( Side.WHITE, "h1", PieceType.ROOK );

        player.setPosition( position.build() );

        assertInboundCommandTranslatedToOpponentMove(
                "usermove e1g1",
                new Move( "e1", "g1" )
        );
    }

    @Test
    public void shouldTranslateOutboundQueensideCastlingToWinboardNotation() {
        PositionBuilder position = new PositionBuilder()
                .add( Side.BLACK, "e8", PieceType.KING )
                .add( Side.BLACK, "a8", PieceType.ROOK );

        player.setPosition( position.setSideOf( "e8" ).build() );

        assertOutboundCommandForPlayerMove(
                new Move( "e8", "c8" ),
                "move e8c8"
        );
    }

    @Test
    public void shouldSendWhiteCheckmateResultWhenWinboardDeliversMate() {
        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "e2", "e4" ) )
                .move( new Move( "g7", "g5" ) )
                .move( new Move( "b1", "c3" ) )
                .move( new Move( "f7", "f5" ) )
                .move( new Move( "d1", "h5" ) )
                .play();

        verify( communicator ).send( "1-0 {Checkmate}" );
        verify( communicator, never() ).send( "0-1 {Checkmate}" );
    }

    @Test
    public void shouldSendObligatoryDrawResultWhenMovesRuleTriggers() {
        player.setPosition( Position.getInitialPosition( new RulesBuilder().movesTillDraw( 1 ).build() ) );

        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "b1", "c3" ) )
                .move( new Move( "b8", "a6" ) )
                .play();

        verify( communicator ).send( "1/2-1/2 {Draw by 1 moves rule}" );
    }

    @Test
    public void shouldSendStalemateResultWhenWinboardMoveCreatesStalemate() {
        Position position = new PositionBuilder()
                .add( Side.WHITE, "d5", PieceType.KING )
                .add( Side.WHITE, "d7", PieceType.PAWN )
                .add( Side.BLACK, "d8", PieceType.KING )
                .build();

        player.setPosition( position );

        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "d5", "d6" ) )
                .play();

        verify( communicator ).send( "1/2-1/2 {Stalemate}" );
    }

    @Test
    public void shouldSendStalemateResultWhenOpponentMoveCreatesStalemate() {
        Position position = new PositionBuilder()
                .add( Side.BLACK, "d4", PieceType.KING )
                .add( Side.BLACK, "d2", PieceType.PAWN )
                .add( Side.WHITE, "e1", PieceType.KING )
                .build();

        player.setPosition( position );

        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "e1", "d1" ) )
                .move( new Move( "d4", "d3" ) )
                .play();

        verify( communicator ).send( "1/2-1/2 {Stalemate}" );
    }

    @Test
    public void shouldSendBlackCheckmateResultWhenEngineDeliversMate() {
        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .move( new Move( "g2", "g4" ) )
                .move( new Move( "d8", "h4" ) )
                .play();

        verify( communicator ).send( "0-1 {Checkmate}" );
        verify( communicator, never() ).send( "1-0 {Checkmate}" );
    }

    @Test
    public void shouldSendResignWithoutSendingCheckmateWhenEngineResigns() {
        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .move( new Move( "g2", "g4" ) )
                .move( Move.RESIGN )
                .play();

        verify( communicator ).send( "resign" );
        verifyNoCheckmateMessagesWereSent();
    }

    @Test
    public void shouldTranslateOutboundDrawOfferToWinboardOfferDrawCommand() {
        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .move( new Move( "g2", "g4" ) )
                .move( Move.OFFER_DRAW )
                .play();

        verify( communicator ).send( "offer draw" );
    }

    @Test
    public void shouldTranslateOutboundClaimDrawToWinboardResultMessage() {
        Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw( 1 ).build();
        Position positionWithClaimDrawPossibility = new PositionBuilder()
                .initial()
                .rules( fastDrawClaimPossibility )
                .build()
                .move( "g1", "f3" )
                .move( "g8", "f6" );
        player.setPosition( positionWithClaimDrawPossibility );

        player.opponentMoved( Move.CLAIM_DRAW );

        verify( communicator ).send( "1/2-1/2 {Draw claimed by 1 moves rule}" );
    }

    @Test
    public void shouldPropagateWinboardResignationToOpponent() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
        builder
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .move( Move.RESIGN )
                .play();

        verify( builder.getOpponent() ).opponentMoved( Move.RESIGN );
    }

    @Test
    public void shouldNotMisclassifyGenericBlackWinResultAsResign() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
        builder
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .move( new Move( "g2", "g4" ) )
                .move( new Move( "d8", "h4" ) )
                .customWinboardResponse( "result 0-1 {Black wins}" )
                .play();

        verify( builder.getOpponent(), never() ).opponentMoved( Move.RESIGN );
    }

    @Test
    public void shouldNotMisclassifyGenericWhiteWinResultAsResign() {
        WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
        builder
                .move( new Move( "e2", "e4" ) )
                .move( new Move( "g7", "g5" ) )
                .move( new Move( "d2", "d4" ) )
                .move( new Move( "f7", "f6" ) )
                .move( new Move( "d1", "h5" ) )
                .customWinboardResponse( "result 1-0 {White wins}" )
                .play();

        verify( builder.getOpponent(), never() ).opponentMoved( Move.RESIGN );
    }

    @Test
    public void shouldNotEmitCheckmateForNonTerminalPartialGame() {
        Player opponent = mock( Player.class );
        player.setOpponent( opponent );

        new WinboardTestGameBuilder( player, communicator )
                .move( new Move( "f2", "f3" ) )
                .move( new Move( "e7", "e5" ) )
                .play();

        verifyNoCheckmateMessagesWereSent();
    }

    private void assertOutboundCommandForPlayerMove( Move playerMove, String expectedWinboardCommand ) {
        player.opponentMoved( playerMove );
        verify( communicator ).send( expectedWinboardCommand );
    }

    private void assertInboundCommandTranslatedToOpponentMove( String winboardCommand, Move expectedMove ) {
        Player opponent = mock( Player.class );
        player.setOpponent( opponent );
        when( communicator.receive() ).thenReturn( winboardCommand );

        commander.processInputFromServer();

        verify( opponent ).opponentMoved( expectedMove );
    }

    private void verifyNoCheckmateMessagesWereSent() {
        verify( communicator, never() ).send( "1-0 {Checkmate}" );
        verify( communicator, never() ).send( "0-1 {Checkmate}" );
    }
}