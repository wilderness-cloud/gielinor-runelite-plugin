package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.FakeScheduler;
import cloud.tobbbles.gielinor.runelite.account.Credentials;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.Status;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import java.util.Collections;
import java.util.function.Consumer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/** How the pusher answers each kind of failure, and the edges PusherTest's happy paths skip. */
public class PusherFailuresTest
{
	private static final CharacterRef ZEZIMA = CharacterRef.newBuilder()
		.setAccountHash(123456789L).setRsn("Zezima").setWorld(302).setProfile("STANDARD").build();

	private FakeScheduler scheduler;
	private StateStore store;
	private FakeTransport transport;
	private Link.AccessToken token;
	private int notified;
	private Pusher pusher;

	@Before
	public void setUp()
	{
		scheduler = new FakeScheduler();
		store = new StateStore();
		transport = new FakeTransport();
		token = Link.AccessToken.of("access-1");
		Credentials credentials = new Credentials()
		{
			@Override
			public void accessToken(Consumer<Link.AccessToken> callback)
			{
				callback.accept(token);
			}

			@Override
			public void invalidateAccessToken()
			{
			}

			@Override
			public void expire(String why)
			{
			}
		};
		pusher = new Pusher(store, transport, credentials, scheduler, scheduler::now, "install-1",
			PluginBuild.newBuilder().setVersion("test").build());
		pusher.setListener(p -> notified++);
	}

	private void steady()
	{
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		transport.ok();
	}

	private void change(int level)
	{
		store.put("skills.ATTACK", Skill.newBuilder().setLevel(level).build());
		pusher.changed();
		scheduler.advance(Pusher.THROTTLE_MS);
	}

	@Test
	public void eachFailureHasItsOwnProblem()
	{
		steady();
		String[][] cases = {
			{"NETWORK", "Can't reach Gielinor. Retrying."},
			{"RESOURCE_EXHAUSTED", "Gielinor asked to slow down. Retrying."},
			{"UNAVAILABLE", "Gielinor is unavailable. Retrying."},
			{"OTHER", "Gielinor answered with an error: OTHER"},
		};
		int level = 1;
		for (String[] c : cases)
		{
			change(level++);
			transport.fail(Status.valueOf(c[0]), 0);
			assertEquals(c[1], pusher.problem());
			scheduler.advance(Pusher.MAX_BACKOFF_MS);
			transport.ok();
			assertNull(pusher.problem());
		}
	}

	@Test
	public void aRefusedPushIsDroppedNotRepeated()
	{
		steady();
		change(70);
		transport.fail(Status.INVALID_ARGUMENT, 0);
		assertEquals("Gielinor refused some data: INVALID_ARGUMENT", pusher.problem());
		assertFalse("what was refused isn't sent again", store.hasChanges());
	}

	@Test
	public void aRefusedLogoutPushHasNothingToDrop()
	{
		steady();
		pusher.loggedOut();
		scheduler.runDue();
		transport.fail(Status.INVALID_ARGUMENT, 0);
		assertTrue(pusher.problem().startsWith("Gielinor refused"));
	}

	@Test
	public void aStaleSeqWithoutTheRelaysLastBacksOff()
	{
		steady();
		change(70);
		transport.fail(Status.FAILED_PRECONDITION, 0);
		int pushes = transport.pushes.size();
		scheduler.advance(Pusher.MIN_BACKOFF_MS - 1);
		assertEquals(pushes, transport.pushes.size());
		scheduler.advance(1);
		assertEquals(pushes + 1, transport.pushes.size());
	}

	@Test
	public void anUnreachableAccountServiceBacksOff()
	{
		token = Link.AccessToken.unavailable();
		assertTrue(token.retryable());
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		assertEquals("Can't reach Gielinor's account service", pusher.problem());
		assertEquals(0, transport.pushes.size());

		token = Link.AccessToken.of("access-1");
		assertFalse(token.retryable());
		scheduler.advance(Pusher.MIN_BACKOFF_MS);
		assertEquals(1, transport.pushes.size());
	}

	@Test
	public void loggingInWhileTheLogoutPushIsInFlightSaysSoNext()
	{
		steady();
		pusher.loggedOut();
		scheduler.runDue();
		pusher.loggedIn(ZEZIMA);
		transport.ok();
		scheduler.runDue();
		assertTrue(transport.last().getCharacter().getLoggedIn());
	}

	@Test
	public void loggingOutWhileAPushIsInFlightSaysSoNext()
	{
		steady();
		change(70);
		pusher.loggedOut();
		pusher.loggedOut();
		transport.ok();
		scheduler.runDue();
		assertFalse(transport.last().getCharacter().getLoggedIn());
	}

	@Test
	public void changesWhileLoggedOutOrBackingOffWait()
	{
		pusher.setEnabled(true);
		pusher.changed();
		assertEquals(0, scheduler.scheduled());

		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		transport.fail(Status.UNAVAILABLE, 30_000);
		pusher.changed();
		pusher.loggedIn(ZEZIMA);
		scheduler.advance(29_999);
		assertEquals("Retry-After is honoured", 1, transport.pushes.size());
		scheduler.advance(1);
		assertEquals(2, transport.pushes.size());
	}

	@Test
	public void loggingOutWhileBackingOffWaitsForTheBackoff()
	{
		steady();
		change(70);
		transport.fail(Status.UNAVAILABLE, 0);
		pusher.loggedOut();
		assertEquals(2, transport.pushes.size());
		scheduler.advance(Pusher.MIN_BACKOFF_MS);
		assertFalse(transport.last().getCharacter().getLoggedIn());
	}

	@Test
	public void switchingOnTwiceIsOnceAndShutDownStops()
	{
		pusher.setEnabled(true);
		int before = notified;
		pusher.setEnabled(true);
		assertEquals(before, notified);

		pusher.loggedIn(ZEZIMA);
		pusher.shutDown();
		scheduler.advance(600_000);
		assertEquals(0, transport.pushes.size());
		assertEquals(0, pusher.lastAckAt());
	}

	@Test
	public void theLastAcknowledgementIsKept()
	{
		steady();
		assertEquals(scheduler.now(), pusher.lastAckAt());
		assertEquals(Collections.emptyList(), pusher.wanted());
		pusher.setListener(null);
		pusher.setEnabled(false);
	}

	@Test
	public void aTimerFiringWhileSwitchedOffSendsNothing()
	{
		steady();
		change(70);
		pusher.setEnabled(false);
		transport.ok();
		scheduler.advance(600_000);
		assertNull(transport.waiting);
	}
}
