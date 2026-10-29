package com.leokom.games.chess.player.winboard;

import com.leokom.games.chess.engine.Move;
import com.leokom.games.chess.engine.Side;
import com.leokom.games.chess.player.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GptTest {
	private static final int WAIT_TILL_QUIT = 1000;
	private static final int PROTOCOL_VERSION = 2;

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
	public void shouldEnableUserMovePrefixesAndFinishInitializationAfterProtover() {
		when( communicator.receive() ).thenReturn( "protover " + PROTOCOL_VERSION );

		commander.processInputFromServer();

		InOrder order = inOrder( communicator );
		order.verify( communicator ).send( "feature done=0" );
		order.verify( communicator ).send( "feature usermove=1" );
		order.verify( communicator ).send( "feature done=1" );
	}

	@Test
	public void shouldSwitchOpponentToRecordingModeWhenForceCommandIsReceived() {
		Player opponent = setMockedOpponent();

		when( communicator.receive() ).thenReturn( "force" );

		commander.processInputFromServer();

		verify( opponent ).switchToRecodingMode();
	}

	@Test
	public void shouldJoinGameForSideToMoveWhenGoCommandIsReceived() {
		Player opponent = setMockedOpponent();

		when( communicator.receive() ).thenReturn( "go" );

		commander.processInputFromServer();

		verify( opponent ).joinGameForSideToMove();
	}

	@Test
	public void shouldResetPositionAndNotifyOpponentWhenNewCommandIsReceived() {
		Player opponent = setMockedOpponent();
		player.opponentMoved( new Move( "e2", "e4" ) );

		assertNull( player.getPosition().getPieceType( "e2" ) );
		assertEquals( Side.BLACK, player.getPosition().getSideToMove() );

		when( communicator.receive() ).thenReturn( "new" );

		commander.processInputFromServer();

		assertNotNull( player.getPosition().getPieceType( "e2" ) );
		assertEquals( Side.WHITE, player.getPosition().getSideToMove() );
		InOrder order = inOrder( opponent );
		order.verify( opponent ).leaveRecordingMode();
		order.verify( opponent ).opponentSuggestsMeStartNewGameBlack();
	}

	@Test
	public void shouldRejectIllegalUserMoveWithoutUpdatingStateOrNotifyingOpponent() {
		Player opponent = setMockedOpponent();

		when( communicator.receive() ).thenReturn( "usermove e2e5" );

		commander.processInputFromServer();

		verify( communicator ).send( "Illegal move: e2e5" );
		verify( opponent, never() ).opponentMoved( new Move( "e2", "e5" ) );
		assertNotNull( player.getPosition().getPieceType( "e2" ) );
		assertEquals( Side.WHITE, player.getPosition().getSideToMove() );
	}

	@Test
	public void shouldPropagateLegalUserMoveToOpponentAndUpdateBoard() {
		Player opponent = setMockedOpponent();

		when( communicator.receive() ).thenReturn( "usermove e2e4" );

		commander.processInputFromServer();

		verify( opponent ).opponentMoved( new Move( "e2", "e4" ) );
		assertNull( player.getPosition().getPieceType( "e2" ) );
		assertNotNull( player.getPosition().getPieceType( "e4" ) );
		assertEquals( Side.BLACK, player.getPosition().getSideToMove() );
	}

	@Test( timeout = WAIT_TILL_QUIT )
	public void shouldStopMainLoopWhenQuitCommandIsReceived() {
		assertFalse( player.needShuttingDown() );

		when( communicator.receive() ).thenReturn( "quit" );

		player.opponentSuggestsMeStartNewGameWhite();

		assertTrue( player.needShuttingDown() );
		verify( communicator ).receive();
	}

	@Test( timeout = WAIT_TILL_QUIT )
	public void shouldIgnoreEmptyInputUntilQuitCommandArrives() {
		assertFalse( player.needShuttingDown() );

		when( communicator.receive() ).thenReturn( null ).thenReturn( "quit" );

		player.opponentSuggestsMeStartNewGameWhite();

		assertTrue( player.needShuttingDown() );
		verify( communicator, times( 2 ) ).receive();
	}

	private Player setMockedOpponent() {
		Player opponent = mock( Player.class );
		player.setOpponent( opponent );
		return opponent;
	}
}