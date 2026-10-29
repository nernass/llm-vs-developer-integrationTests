package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.*;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Extended integration tests for WinboardPlayer + WinboardCommanderImpl wiring.
 *
 * Only the low-level WinboardCommunicator transport is mocked.
 * All internal components (WinboardPlayer, WinboardCommanderImpl, Position,
 * Move, Rules) are used as real instances.
 */
public class ClaudeTest {

	private static final int WAIT_TILL_QUIT = 5000;

	private WinboardCommunicator communicator;
	private WinboardCommander commander;
	private WinboardPlayer player;

	@Before
	public void prepare() {
		communicator = mock( WinboardCommunicator.class );
		commander = new WinboardCommanderImpl( communicator );
		player = new WinboardPlayer( commander );
	}

	// ===== Lifecycle: initialization sequence =====

	@Test
	public void shouldSendFeatureDone0ImmediatelyOnConstruction() {
		verify( communicator ).send( "feature done=0" );
	}

	@Test
	public void shouldEnableUserMovePrefixesAndFinishInitOnProtover() {
		when( communicator.receive() ).thenReturn( "protover 2" );
		commander.processInputFromServer();

		InOrder order = inOrder( communicator );
		order.verify( communicator ).send( "feature done=0" );
		order.verify( communicator ).send( "feature usermove=1" );
		order.verify( communicator ).send( "feature done=1" );
	}

	// ===== Lifecycle: quit =====

	@Test( timeout = WAIT_TILL_QUIT )
	public void shouldTerminateMainLoopOnQuitCommand() {
		when( communicator.receive() ).thenReturn( "quit" );
		player.opponentSuggestsMeStartNewGameWhite();
		assertTrue( player.needShuttingDown() );
	}

	@Test( timeout = WAIT_TILL_QUIT )
	public void shouldHandleNullInputGracefullyBeforeQuit() {
		when( communicator.receive() )
				.thenReturn( null )
				.thenReturn( null )
				.thenReturn( "quit" );
		player.opponentSuggestsMeStartNewGameWhite();
		assertTrue( player.needShuttingDown() );
	}

	// ===== Lifecycle: force / go / new =====

	@Test
	public void shouldDelegateForceCommandToOpponentRecordingMode() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "force" );
		commander.processInputFromServer();

		verify( opponent ).switchToRecodingMode();
	}

	@Test
	public void shouldDelegateGoCommandToOpponentJoinGame() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "go" );
		commander.processInputFromServer();

		verify( opponent ).joinGameForSideToMove();
	}

	@Test
	public void shouldResetPositionOnNewCommand() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		// make a move to change initial state
		player.opponentMoved( new Move( "e2", "e4" ) );
		assertNull( player.getPosition().getPieceType( "e2" ) );

		when( communicator.receive() ).thenReturn( "new" );
		commander.processInputFromServer();

		// position reset: pawn back on e2
		assertNotNull( player.getPosition().getPieceType( "e2" ) );
		assertEquals( Side.WHITE, player.getPosition().getSideToMove() );
	}

	@Test
	public void shouldNotifyOpponentAboutNewGameOnNewCommand() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "new" );
		commander.processInputFromServer();

		InOrder order = inOrder( opponent );
		order.verify( opponent ).leaveRecordingMode();
		order.verify( opponent ).opponentSuggestsMeStartNewGameBlack();
	}

	// ===== Illegal move detection =====

	@Test
	public void shouldRejectIllegalMoveAndSendErrorToWinboard() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		// e2e5 is not a legal pawn move from the initial position
		when( communicator.receive() ).thenReturn( "usermove e2e5" );
		commander.processInputFromServer();

		verify( communicator ).send( "Illegal move: e2e5" );
		verify( opponent, never() ).opponentMoved( any( Move.class ) );
	}

	@Test
	public void shouldNotChangeSideToMoveAfterIllegalMove() {
		player.setOpponent( mock( Player.class ) );

		when( communicator.receive() ).thenReturn( "usermove e2e5" );
		commander.processInputFromServer();

		assertEquals( Side.WHITE, player.getPosition().getSideToMove() );
	}

	@Test
	public void shouldNotMoveThePieceAfterIllegalMove() {
		player.setOpponent( mock( Player.class ) );

		when( communicator.receive() ).thenReturn( "usermove e2e5" );
		commander.processInputFromServer();

		assertNotNull( player.getPosition().getPieceType( "e2" ) );
	}

	// ===== Legal user move processing =====

	@Test
	public void shouldUpdatePositionAfterLegalUserMove() {
		player.setOpponent( mock( Player.class ) );

		when( communicator.receive() ).thenReturn( "usermove d2d4" );
		commander.processInputFromServer();

		assertNull( player.getPosition().getPieceType( "d2" ) );
		assertNotNull( player.getPosition().getPieceType( "d4" ) );
		assertEquals( Side.BLACK, player.getPosition().getSideToMove() );
	}

	@Test
	public void shouldPropagateUserMoveToOpponent() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "usermove e2e4" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "e2", "e4" ) );
	}

	// ===== Consecutive user moves to verify state consistency =====

	@Test
	public void shouldHandleTwoConsecutiveUserMoves() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		// white move
		when( communicator.receive() ).thenReturn( "usermove e2e4" );
		commander.processInputFromServer();
		verify( opponent ).opponentMoved( new Move( "e2", "e4" ) );

		// simulate black response via player.opponentMoved
		player.opponentMoved( new Move( "e7", "e5" ) );

		// another white move
		when( communicator.receive() ).thenReturn( "usermove g1f3" );
		commander.processInputFromServer();
		verify( opponent ).opponentMoved( new Move( "g1", "f3" ) );

		assertEquals( Side.BLACK, player.getPosition().getSideToMove() );
	}

	// ===== Outbound move translation =====

	@Test
	public void shouldTranslateNormalMoveToWinboardProtocol() {
		player.opponentMoved( new Move( "e2", "e4" ) );
		verify( communicator ).send( "move e2e4" );
	}

	@Test
	public void shouldTranslateResignMoveToWinboard() {
		player.opponentMoved( Move.RESIGN );
		verify( communicator ).send( "resign" );
	}

	@Test
	public void shouldTranslateAcceptDrawToWinboard() {
		player.opponentMoved( Move.ACCEPT_DRAW );
		verify( communicator ).send( "offer draw" );
	}

	@Test
	public void shouldTranslateOfferDrawToWinboard() {
		player.opponentMoved( Move.OFFER_DRAW );
		verify( communicator ).send( "offer draw" );
	}

	// ===== Inbound protocol message translation =====

	@Test
	public void shouldTranslateDrawCommandToOfferDraw() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "draw" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.OFFER_DRAW );
	}

	@Test
	public void shouldTranslateResultDrawToAcceptDraw() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 1/2-1/2 {Draw agreed}" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.ACCEPT_DRAW );
	}

	@Test
	public void shouldTranslateResultResignToResignMove() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 0-1 {White resigns}" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.RESIGN );
	}

	// ===== Promotion translation (both directions) =====

	@Test
	public void shouldTranslateInboundQueenPromotionToUpperCase() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "a7" )
				.setSideOf( "a7" );
		player.setPosition( position.build() );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "usermove a7a8q" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "a7", "a8Q" ) );
	}

	@Test
	public void shouldTranslateOutboundKnightPromotionToLowerCase() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "b7" )
				.setSideOf( "b7" );
		player.setPosition( position.build() );

		player.opponentMoved( new Move( "b7", "b8N" ) );
		verify( communicator ).send( "move b7b8n" );
	}

	@Test
	public void shouldTranslateInboundRookPromotionToUpperCase() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "c7" )
				.setSideOf( "c7" );
		player.setPosition( position.build() );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "usermove c7c8r" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "c7", "c8R" ) );
	}

	@Test
	public void shouldTranslateOutboundBishopPromotionToLowerCase() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "d7" )
				.setSideOf( "d7" );
		player.setPosition( position.build() );

		player.opponentMoved( new Move( "d7", "d8B" ) );
		verify( communicator ).send( "move d7d8b" );
	}

	@Test
	public void shouldTranslateInboundPromotionWithCapture() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "f7" )
				.add( Side.BLACK, "g8", PieceType.QUEEN )
				.setSideOf( "f7" );
		player.setPosition( position.build() );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "usermove f7g8q" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "f7", "g8Q" ) );
	}

	// ===== Castling (both directions) =====

	@Test
	public void shouldTranslateInboundWhiteKingsideCastling() {
		PositionBuilder position = new PositionBuilder()
				.add( Side.WHITE, "e1", PieceType.KING )
				.add( Side.WHITE, "h1", PieceType.ROOK );
		player.setPosition( position.build() );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "usermove e1g1" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "e1", "g1" ) );
	}

	@Test
	public void shouldTranslateOutboundBlackQueensideCastling() {
		PositionBuilder position = new PositionBuilder()
				.add( Side.BLACK, "e8", PieceType.KING )
				.add( Side.BLACK, "a8", PieceType.ROOK )
				.setSideOf( "e8" );
		player.setPosition( position.build() );

		player.opponentMoved( new Move( "e8", "c8" ) );
		verify( communicator ).send( "move e8c8" );
	}

	@Test
	public void shouldTranslateOutboundWhiteKingsideCastling() {
		PositionBuilder position = new PositionBuilder()
				.add( Side.WHITE, "e1", PieceType.KING )
				.add( Side.WHITE, "h1", PieceType.ROOK );
		player.setPosition( position.build() );

		player.opponentMoved( new Move( "e1", "g1" ) );
		verify( communicator ).send( "move e1g1" );
	}

	// ===== Game-over: checkmate =====

	@Test
	public void shouldSendWhiteWinsCheckmateWhenWinboardDeliversMate() {
		// Scholar's mate variant
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
	public void shouldSendBlackWinsCheckmateWhenEngineDeliversMate() {
		// Fool's mate
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
	public void shouldNotSendCheckmateWhenGameIsNotTerminal() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "e2", "e4" ) )
				.move( new Move( "e7", "e5" ) )
				.play();

		verify( communicator, never() ).send( contains( "Checkmate" ) );
	}

	// ===== Game-over: stalemate =====

	@Test
	public void shouldSendStalemateWhenWinboardMoveProducesStalemate() {
		final Position position = new PositionBuilder()
				.add( Side.WHITE, "d5", PieceType.KING )
				.add( Side.WHITE, "d7", PieceType.PAWN )
				.add( Side.BLACK, "d8", PieceType.KING )
				.build();
		player.setPosition( position );

		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "d5", "d6" ) )
				.play();

		verify( communicator ).send( startsWith( "1/2-1/2" ) );
		verify( communicator ).send( contains( "Stalemate" ) );
	}

	@Test
	public void shouldSendStalemateWhenEngineOpponentMoveProducesStalemate() {
		final Position position = new PositionBuilder()
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

	// ===== Game-over: obligatory draw =====

	@Test
	public void shouldSendObligatoryDrawWhenMoveCountReached() {
		player.setPosition( Position.getInitialPosition( new RulesBuilder().movesTillDraw( 1 ).build() ) );

		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "b1", "c3" ) )
				.move( new Move( "b8", "a6" ) )
				.play();

		verify( communicator, atLeastOnce() ).send( startsWith( "1/2-1/2" ) );
	}

	// ===== Draw claim disambiguation =====

	@Test
	public void shouldClassifyDrawCommandAsClaimDrawWhenClaimIsLegal() {
		Rules fastClaimDraw = new RulesBuilder().movesTillClaimDraw( 1 ).build();
		Position pos = new PositionBuilder()
				.initial()
				.rules( fastClaimDraw )
				.build()
				.move( "g1", "f3" )
				.move( "g8", "f6" );
		player.setPosition( pos );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "draw" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.CLAIM_DRAW );
	}

	@Test
	public void shouldSendUINotificationWhenClaimDrawViaDrawCommandWithAdjudicationDisabled() {
		Rules fastClaimDraw = new RulesBuilder().movesTillClaimDraw( 1 ).build();
		Position pos = new PositionBuilder()
				.initial()
				.rules( fastClaimDraw )
				.build()
				.move( "b1", "a3" )
				.move( "b8", "a6" );
		player.setPosition( pos );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "draw" );
		commander.processInputFromServer();

		verify( communicator ).send( "1/2-1/2 {Draw claim received from UI by 1 moves rule}" );
		verify( opponent ).opponentMoved( Move.CLAIM_DRAW );
	}

	@Test
	public void shouldClassifyDrawResultAsClaimDrawWhenClaimIsLegalWithAdjudication() {
		Rules fastClaimDraw = new RulesBuilder().movesTillClaimDraw( 1 ).build();
		Position pos = new PositionBuilder()
				.initial()
				.rules( fastClaimDraw )
				.build()
				.move( "g1", "f3" )
				.move( "g8", "f6" );
		player.setPosition( pos );

		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 1/2-1/2 {adjudicated draw}" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.CLAIM_DRAW );
	}

	@Test
	public void shouldClassifyDrawOfferAsOfferDrawWhenNoClaimIsLegal() {
		// default position: claim draw is not legal
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "draw" );
		commander.processInputFromServer();

		verify( opponent ).opponentMoved( Move.OFFER_DRAW );
	}

	// ===== Resign: inbound / outbound =====

	@Test
	public void shouldTranslateResignFromEngineToWinboard() {
		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.move( new Move( "g2", "g4" ) )
				.move( Move.RESIGN )
				.play();

		verify( communicator ).send( "resign" );
		verify( communicator, never() ).send( contains( "Checkmate" ) );
	}

	@Test
	public void shouldPropagateWinboardResignToOpponent() {
		WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
		builder
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.move( Move.RESIGN )
				.play();

		verify( builder.getOpponent() ).opponentMoved( Move.RESIGN );
	}

	// ===== False-positive guards =====

	@Test
	public void shouldNotMisclassifyBlackWinsResultAsResign() {
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
	public void shouldNotMisclassifyWhiteWinsResultAsResign() {
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

	// ===== Draw offer from engine =====

	@Test
	public void shouldSendOfferDrawWhenEngineOffersDrawMidGame() {
		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "e2", "e4" ) )
				.move( new Move( "e7", "e5" ) )
				.move( new Move( "g1", "f3" ) )
				.move( Move.OFFER_DRAW )
				.play();

		verify( communicator ).send( "offer draw" );
	}

	// ===== Claim draw outbound =====

	@Test
	public void shouldSendClaimDrawByMovesCountWhenEngineClaimsDraw() {
		int movesTillClaimDraw = 1;
		Rules rules = new RulesBuilder().movesTillClaimDraw( movesTillClaimDraw ).build();
		Position pos = Position.getInitialPosition( rules );
		player.setPosition( pos );

		player.opponentMoved( new Move( "g1", "f3" ) );

		// simulate WinBoard user move that gives us the position where claim draw is possible
		player.setOpponent( mock( Player.class ) );
		when( communicator.receive() ).thenReturn( "usermove g8f6" );
		commander.processInputFromServer();

		// now engine sends claim draw
		reset( communicator );
		player.opponentMoved( Move.CLAIM_DRAW );

		verify( communicator ).send( "1/2-1/2 {Draw claimed by 1 moves rule}" );
	}

	// ===== Position state after game-over event from WinBoard =====

	@Test
	public void shouldMarkPositionTerminalAfterAcceptDrawReceived() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 1/2-1/2 {Draw by agreement}" );
		commander.processInputFromServer();

		assertTrue( player.getPosition().isTerminal() );
	}

	@Test
	public void shouldMarkPositionTerminalAfterResignReceived() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 1-0 {Black resigns}" );
		commander.processInputFromServer();

		assertTrue( player.getPosition().isTerminal() );
	}
}
