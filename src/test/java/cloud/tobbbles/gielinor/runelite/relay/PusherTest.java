package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.FakeScheduler;
import cloud.tobbbles.gielinor.runelite.account.Credentials;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.PushResult;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.Status;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import cloud.tobbbles.gielinor.runelite.v1.PushResponse;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import com.google.protobuf.FieldMask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class PusherTest
{
	private static final CharacterRef ZEZIMA = CharacterRef.newBuilder()
		.setAccountHash(123456789L).setRsn("Zezima").setWorld(302).setProfile("STANDARD").build();

	private FakeScheduler scheduler;
	private StateStore store;
	private FakeTransport transport;
	private FakeCredentials credentials;
	private Pusher pusher;

	@Before
	public void setUp()
	{
		scheduler = new FakeScheduler();
		store = new StateStore();
		transport = new FakeTransport();
		credentials = new FakeCredentials();
		pusher = new Pusher(store, transport, credentials, scheduler, scheduler::now, "install-1",
			PluginBuild.newBuilder().setVersion("0.1.0").build());
	}

	private static Skill skill(int level)
	{
		return Skill.newBuilder().setLevel(level).setXp(level * 1000).build();
	}

	/** Linked, logged in, the first push answered: a steady state to test from. */
	private void steady()
	{
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		transport.ok();
	}

	private void change(String path, int level)
	{
		store.put(path, skill(level));
		pusher.changed();
	}

	@Test
	public void sendsNothingUntilLinkedAndLoggedIn()
	{
		pusher.loggedIn(ZEZIMA);
		store.put("skills.ATTACK", skill(70));
		pusher.changed();
		scheduler.advance(120_000);
		assertEquals(0, transport.pushes.size());

		pusher.setEnabled(true);
		scheduler.runDue();

		assertEquals(1, transport.pushes.size());
		PushRequest push = transport.last();
		assertEquals("install-1", push.getClientId());
		assertEquals("0.1.0", push.getPlugin().getVersion());
		assertTrue(push.getCharacter().getLoggedIn());
		assertEquals(123456789L, push.getCharacter().getAccountHash());
		assertEquals(Collections.singletonList("skills.ATTACK"), push.getUpdateMask().getPathsList());
		assertEquals(70, push.getState().getSkillsOrThrow("ATTACK").getLevel());
		assertEquals("access-1", transport.tokens.get(0));
	}

	@Test
	public void throttlesABurstIntoOnePush()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(500);
		change("skills.STRENGTH", 70);
		scheduler.advance(1_000);
		change("skills.DEFENCE", 70);
		assertEquals(1, transport.pushes.size());

		scheduler.advance(500);

		assertEquals(2, transport.pushes.size());
		assertEquals(Arrays.asList("skills.ATTACK", "skills.DEFENCE", "skills.STRENGTH"),
			transport.last().getUpdateMask().getPathsList());
	}

	@Test
	public void steadyChangesStillGoOutEveryFewSeconds()
	{
		steady();
		for (int tick = 0; tick < 10; tick++)
		{
			change("skills.ATTACK", 60 + tick);
			scheduler.advance(600);
			if (transport.waiting != null)
			{
				transport.ok();
			}
		}
		assertTrue("pushes while changes keep coming: " + transport.pushes.size(), transport.pushes.size() >= 3);
	}

	@Test
	public void onePushAtATimeAndChangesInFlightGoNext()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);
		assertEquals(2, transport.pushes.size());

		change("skills.DEFENCE", 60);
		scheduler.advance(10_000);
		assertEquals("nothing new while one is in flight", 2, transport.pushes.size());

		transport.ok();
		scheduler.advance(2_000);

		assertEquals(3, transport.pushes.size());
		assertEquals(Collections.singletonList("skills.DEFENCE"), transport.last().getUpdateMask().getPathsList());
	}

	@Test
	public void heartbeatsWhileNothingChangesAtTheRelaysPace()
	{
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		transport.ok(PushResponse.newBuilder().setNextHeartbeatS(90).build());

		scheduler.advance(89_999);
		assertEquals(1, transport.pushes.size());
		scheduler.advance(1);

		assertEquals(2, transport.pushes.size());
		assertEquals(0, transport.last().getUpdateMask().getPathsCount());
		assertFalse(transport.last().hasState());
	}

	@Test
	public void seqRisesStrictlyAndStartsFromTheClock()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);
		transport.ok();

		long first = transport.pushes.get(0).getSeq();
		long second = transport.pushes.get(1).getSeq();
		assertTrue(first >= 1_760_000_000_000L);
		assertTrue(second > first);
	}

	@Test
	public void aFailedPushLosesNothingAndBacksOff()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);
		transport.fail(Status.NETWORK, 0);
		assertNotNull(pusher.problem());

		change("skills.DEFENCE", 60);
		scheduler.advance(1_999);
		assertEquals("a change doesn't cut a backoff short", 2, transport.pushes.size());
		scheduler.advance(1);
		assertEquals(3, transport.pushes.size());
		assertEquals(Arrays.asList("skills.ATTACK", "skills.DEFENCE"), transport.last().getUpdateMask().getPathsList());

		transport.fail(Status.UNAVAILABLE, 0);
		scheduler.advance(3_999);
		assertEquals(3, transport.pushes.size());
		scheduler.advance(1);
		assertEquals("backoff doubles", 4, transport.pushes.size());

		transport.ok();
		assertNull(pusher.problem());
		assertFalse(store.hasChanges());
	}

	@Test
	public void honoursRetryAfter()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);
		transport.fail(Status.RESOURCE_EXHAUSTED, 10_000);

		scheduler.advance(9_999);
		assertEquals(2, transport.pushes.size());
		scheduler.advance(1);
		assertEquals(3, transport.pushes.size());
	}

	@Test
	public void retriesOnceWithAFreshTokenThenBacksOff()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);

		transport.fail(Status.UNAUTHENTICATED, 0);
		assertEquals(1, credentials.invalidated);
		scheduler.runDue();
		assertEquals("retried at once", 3, transport.pushes.size());

		transport.fail(Status.UNAUTHENTICATED, 0);
		scheduler.runDue();
		assertEquals("then backs off", 3, transport.pushes.size());
		scheduler.advance(2_000);
		assertEquals(4, transport.pushes.size());
	}

	@Test
	public void aTokenThatCantPushEndsTheLink()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);

		transport.fail(Status.PERMISSION_DENIED, 0);
		scheduler.advance(600_000);

		assertEquals(1, credentials.expired);
		assertEquals(2, transport.pushes.size());
	}

	@Test
	public void aStaleSeqJumpsPastTheRelaysLast()
	{
		steady();
		change("skills.ATTACK", 70);
		scheduler.advance(2_000);
		long relayLast = transport.last().getSeq() + 1_000_000;

		PushResult stale = RelayClient.PushResult.of(Status.FAILED_PRECONDITION, "stale");
		stale.lastSeq = relayLast;
		transport.answer(stale);
		scheduler.runDue();

		assertEquals(3, transport.pushes.size());
		assertTrue(transport.last().getSeq() > relayLast);
		assertEquals(Collections.singletonList("skills.ATTACK"), transport.last().getUpdateMask().getPathsList());
	}

	@Test
	public void sendsAgainWhatTheRelayIsMissing()
	{
		steady();
		change("skills.ATTACK", 70);
		change("skills.DEFENCE", 60);
		scheduler.advance(2_000);
		transport.ok(PushResponse.newBuilder().setMissing(FieldMask.newBuilder().addPaths("skills")).build());

		scheduler.advance(2_000);

		assertEquals(3, transport.pushes.size());
		assertEquals(Arrays.asList("skills.ATTACK", "skills.DEFENCE"), transport.last().getUpdateMask().getPathsList());
	}

	@Test
	public void keepsWhatTheRelayWants()
	{
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		transport.ok(PushResponse.newBuilder().setWanted(FieldMask.newBuilder().addPaths("collection_log")).build());

		assertEquals(Collections.singletonList("collection_log"), pusher.wanted());
	}

	@Test
	public void saysSoOnceOnLogoutThenStops()
	{
		steady();
		change("skills.ATTACK", 70);
		pusher.loggedOut();
		scheduler.runDue();

		PushRequest logout = transport.last();
		assertFalse(logout.getCharacter().getLoggedIn());
		assertFalse("no state with a logged-out character", logout.hasState());
		assertEquals(0, logout.getUpdateMask().getPathsCount());

		transport.ok();
		scheduler.advance(600_000);
		assertEquals(2, transport.pushes.size());
		assertNull(pusher.character());

		// The change waits for the next login of the same character.
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		assertEquals(Collections.singletonList("skills.ATTACK"), transport.last().getUpdateMask().getPathsList());
	}

	@Test
	public void anotherCharacterStartsFromAnEmptyStore()
	{
		steady();
		change("skills.ATTACK", 70);
		pusher.loggedIn(ZEZIMA.toBuilder().setAccountHash(987654321L).setRsn("Lynx Titan").build());
		scheduler.advance(2_000);

		assertFalse(store.hasChanges());
		assertEquals(987654321L, transport.last().getCharacter().getAccountHash());
		assertEquals(0, transport.last().getUpdateMask().getPathsCount());
	}

	@Test
	public void switchingOffStopsPushing()
	{
		steady();
		pusher.setEnabled(false);
		change("skills.ATTACK", 70);
		scheduler.advance(600_000);

		assertEquals(1, transport.pushes.size());
		assertTrue(store.hasChanges());
	}

	@Test
	public void anUnlinkedLinkStopsPushing()
	{
		credentials.unlinked = true;
		pusher.setEnabled(true);
		pusher.loggedIn(ZEZIMA);
		scheduler.runDue();
		scheduler.advance(600_000);

		assertEquals(0, transport.pushes.size());
		assertEquals("Not linked", pusher.problem());
	}

	/** Holds each push until the test answers it. */
	private static final class FakeTransport implements Pusher.Transport
	{
		final List<PushRequest> pushes = new ArrayList<>();
		final List<String> tokens = new ArrayList<>();
		Consumer<PushResult> waiting;

		@Override
		public void push(PushRequest request, String accessToken, Consumer<PushResult> done)
		{
			assertNull("one push at a time", waiting);
			pushes.add(request);
			tokens.add(accessToken);
			waiting = done;
		}

		PushRequest last()
		{
			return pushes.get(pushes.size() - 1);
		}

		void ok()
		{
			ok(PushResponse.getDefaultInstance());
		}

		void ok(PushResponse response)
		{
			answer(PushResult.ok(response.toBuilder().setAckedSeq(last().getSeq()).build()));
		}

		void fail(Status status, long retryAfterMs)
		{
			PushResult result = PushResult.of(status, status.name());
			result.retryAfterMs = retryAfterMs;
			answer(result);
		}

		void answer(PushResult result)
		{
			Consumer<PushResult> done = waiting;
			waiting = null;
			done.accept(result);
		}
	}

	private static final class FakeCredentials implements Credentials
	{
		boolean unlinked;
		int invalidated;
		int expired;

		@Override
		public void accessToken(Consumer<Link.AccessToken> callback)
		{
			callback.accept(unlinked ? Link.AccessToken.unlinked() : Link.AccessToken.of("access-1"));
		}

		@Override
		public void invalidateAccessToken()
		{
			invalidated++;
		}

		@Override
		public void expire(String why)
		{
			expired++;
		}
	}
}
