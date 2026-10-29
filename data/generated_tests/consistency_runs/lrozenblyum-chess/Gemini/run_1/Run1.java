
package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.player.Player;
import com.leokom.games.chess.engine.*;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Integration test verifying the WinBoard protocol translation boundary
 * between {@link WinboardPlayer} (high-level Player interface) and
 * {@link WinboardCommanderImpl} (mid-level protocol parser/emitter).
 *
 * The only component mocked here is the low-level {@link WinboardCommunicator}
 * transport; {@link WinboardPlayer}, {@link WinboardCommanderImpl}, {@link Position},
 * {@link Move}, {@link Rules} and the opponent {@link Player} callback collaborator
 * are exercised through real wiring (per the mocking policy: only the external
 * I/O transport boundary is a test double).
 */
public class WinBoardPlayerIntegrationTest {

	private WinboardCommunicator communicator;
	private WinboardCommander commander;
	private WinboardPlayer player;

	@Before
	public void prepare() {
		// fresh mock per test prevents stub bleed-over between tests
		communicator = mock( WinboardCommunicator.class );
		commander = new WinboardCommanderImpl( communicator );
		player = new WinboardPlayer( commander );
	}

	@Test
	public void switchesWinboardToSetUpMode() {
		// The commander mock is actually EMULATOR OF Winboard behaviour!
		verify( communicator ).send( "feature done=0" );
	}

	@Test
	public void offerDrawListenerCalled() {
		final Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		// low-level
		when( communicator.receive() ).thenReturn( "draw" );
		// mid-level processing
		commander.processInputFromServer();
		// top-level component has set up the commander's listener correctly
		verify( opponent ).opponentMoved( Move.OFFER_DRAW );
	}

	@Test
	public void informWinboardAboutResign() {
		assertTranslationOfCommandFromPlayerToWinboardClient(
				Move.RESIGN, "resign" );
	}

	@Test
	public void informWinboardAboutAcceptDraw() {
		assertTranslationOfCommandFromPlayerToWinboardClient(
				Move.ACCEPT_DRAW, "offer draw" );
	}

	@Test
	public void informOpponentAboutAcceptDraw() {
		assertTranslationOfReceivedCommandToMoveForOpponent(
				"result 1/2-1/2 {some maybe impl. specific message}", Move.ACCEPT_DRAW );
	}

	@Test
	public void resignListenerCalled() {
		final Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( "result 1-0 {Black resigns}" );
		commander.processInputFromServer();
		
		verify( opponent ).opponentMoved( Move.RESIGN );
	}

	@Test
	public void claimDrawFromUIReceivedWhenAdjudicationEnabled() {
		Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw( 1 ).build();
		Position positionWithClaimDrawPossibility =
				new PositionBuilder()
						.initial()
						.rules( fastDrawClaimPossibility ).
						build().
						move( "g1", "f3" ).
						move( "g8", "f6" );
		player.setPosition( positionWithClaimDrawPossibility );

		assertTranslationOfReceivedCommandToMoveForOpponent(
				"result 1/2-1/2 {draw claimed by UI - this string is not part of specification}",
				Move.CLAIM_DRAW );
	}

	@Test
	public void claimDrawFromUIReceivedWhenAdjudicationDisabled() {
		Rules fastDrawClaimPossibility = new RulesBuilder().movesTillClaimDraw( 1 ).build();
		Position positionWithClaimDrawPossibility =
				new PositionBuilder()
						.initial()
						.rules( fastDrawClaimPossibility ).
						build().
						move( "b1", "a3" ).
						move( "b8", "a6" );
		player.setPosition( positionWithClaimDrawPossibility );

		assertTranslationOfReceivedCommandToMoveForOpponent(
				"draw",
				Move.CLAIM_DRAW );
		verify( communicator ).send( "1/2-1/2 {Draw claim received from UI by 1 moves rule}" );
	}

	@Test
	public void userMoveNoException() {
		player.setOpponent( mock( Player.class ) );

		when( communicator.receive() ).thenReturn( "usermove e2e4" );
		commander.processInputFromServer();
		
		assertEquals( Side.BLACK, player.getPosition().getSideToMove() );
	}

	@Test
	public void promotionCorrectlyTranslatedToCommonStandard() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "f7" )
				.add( Side.BLACK, "g8", PieceType.QUEEN )
				.setSideOf( "f7" );

		player.setPosition( position.build() );

		assertTranslationOfReceivedCommandToMoveForOpponent(
				"usermove f7g8q", new Move( "f7", "g8Q" ) );
	}

	@Test
	public void promotionCorrectlyTranslatedFromCommonStandard() {
		PositionBuilder position = new PositionBuilder()
				.addPawn( Side.WHITE, "f7" )
				.setSideOf( "f7" );

		player.setPosition( position.build() );

		assertTranslationOfCommandFromPlayerToWinboardClient(
				new Move( "f7", "f8Q" ), "move f7f8q" );
	}

	@Test
	public void castlingCorrectlyTranslatedToPlayer() {
		PositionBuilder position = new PositionBuilder()
				.add( Side.WHITE, "e1", PieceType.KING )
				.add( Side.WHITE, "h1", PieceType.ROOK );

		player.setPosition( position.build() );

		assertTranslationOfReceivedCommandToMoveForOpponent(
				"usermove e1g1", new Move( "e1", "g1" ) );
	}

	@Test
	public void castlingCorrectlyTranslatedToWinboardClient() {
		PositionBuilder position = new PositionBuilder()
				.add( Side.BLACK, "e8", PieceType.KING )
				.add( Side.BLACK, "a8", PieceType.ROOK );

		player.setPosition( position.setSideOf( "e8" ).build() );

		assertTranslationOfCommandFromPlayerToWinboardClient(
				new Move( "e8", "c8" ), "move e8c8" );
	}

	@Test
	public void checkmateWhenWinboardWins() {
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
	public void obligatoryDraw() {
		player.setPosition( Position.getInitialPosition( new RulesBuilder().movesTillDraw( 1 ).build() ) );

		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
		builder
				.move( new Move( "b1", "c3" ) )
				.move( new Move( "b8", "a6" ) )
				.play();

		verify( communicator, atLeastOnce() ).send( startsWith( "1/2-1/2" ) );
	}

	@Test
	public void stalemateSentDueToWinboardMove() {
		final Position position = new PositionBuilder()
				.add( Side.WHITE, "d5", PieceType.KING )
				.add( Side.WHITE, "d7", PieceType.PAWN )
				.add( Side.BLACK, "d8", PieceType.KING )
				.build();

		player.setPosition( position );
		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );

		builder.move( new Move( "d5", "d6" ) ).play();

		verify( communicator ).send( startsWith( "1/2-1/2" ) );
		verify( communicator ).send( contains( "Stalemate" ) );
	}

	@Test
	public void stalemateSentDueToOpponentMove() {
		final Position position = new PositionBuilder()
				.add( Side.BLACK, "d4", PieceType.KING )
				.add( Side.BLACK, "d2", PieceType.PAWN )
				.add( Side.WHITE, "e1", PieceType.KING )
				.build();

		player.setPosition( position );
		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );

		builder.move( new Move( "e1", "d1" ) ).move( new Move( "d4", "d3" ) ).play();

		verify( communicator ).send( "1/2-1/2 {Stalemate}" );
	}

	@Test
	public void checkmateFromPlayerToWinboard() {
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
	public void resignFromPlayerToWinboard() {
		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.move( new Move( "g2", "g4" ) )
				.move( Move.RESIGN )
				.play();

		verify( communicator ).send( "resign" );
		verify( communicator, never() ).send( "1-0 {LeokomChess : checkmate}" );
	}

	@Test
	public void offerDrawFromPlayerToWinboard() {
		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.move( new Move( "g2", "g4" ) )
				.move( Move.OFFER_DRAW )
				.play();

		verify( communicator ).send( "offer draw" );
	}

	@Test
	public void resignFromWinboardToPlayer() {
		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
		builder
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.move( Move.RESIGN )
				.play();

		verify( builder.getOpponent() ).opponentMoved( Move.RESIGN );
	}

	@Test
	public void noFalsePositiveResign() {
		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
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
	public void noFalsePositiveResignWhenWhiteWins() {
		final WinboardTestGameBuilder builder = new WinboardTestGameBuilder( player, communicator );
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
	public void noCheckmateNoFalseInforming() {
		final Player opponent = mock( Player.class );
		player.setOpponent( opponent );

		new WinboardTestGameBuilder( player, communicator )
				.move( new Move( "f2", "f3" ) )
				.move( new Move( "e7", "e5" ) )
				.play();

		verify( communicator, never() ).send( "0-1 {LeokomChess : checkmate}" );
	}

	private void assertTranslationOfCommandFromPlayerToWinboardClient( Move playerMove, String commandSentToWinboardClient ) {
		player.opponentMoved( playerMove );
		verify( communicator ).send( commandSentToWinboardClient );
	}

	private void assertTranslationOfReceivedCommandToMoveForOpponent( String winboardReceivedMessage, Move move ) {
		Player opponent = mock( Player.class );

		player.setOpponent( opponent );

		when( communicator.receive() ).thenReturn( winboardReceivedMessage );

		commander.processInputFromServer();

		verify( opponent ).opponentMoved( move );
	}
}
