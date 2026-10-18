package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.Scheduler;
import cloud.tobbbles.gielinor.runelite.account.Credentials;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.PushResult;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the {@link StateStore}'s changes to the relay (docs/design.md, "Pushing").
 *
 * <ul>
 * <li><b>Throttled:</b> a change starts a {@value #THROTTLE_MS} ms timer and later changes ride
 * along, so a burst (a trip's worth of inventory changes) is one push, and steady changes (XP every
 * tick) still go out every few seconds.</li>
 * <li><b>One push at a time</b>, with {@code seq} strictly increasing across restarts (at least
 * the current time in milliseconds).</li>
 * <li><b>Changes are cleared only by an acknowledgement</b>; a push that fails loses nothing, and
 * the next one carries current values.</li>
 * <li><b>Heartbeats</b> (an empty mask) keep presence while nothing changes, every
 * {@code next_heartbeat_s} the relay asks for.</li>
 * <li><b>Backs off</b> on failures, honouring Retry-After; new changes don't cut a backoff short.</li>
 * </ul>
 */
@Slf4j
public class Pusher
{
	static final long THROTTLE_MS = 2_000;
	static final long MIN_BACKOFF_MS = 2_000;
	static final long MAX_BACKOFF_MS = 300_000;
	static final long DEFAULT_HEARTBEAT_MS = 60_000;

	public interface Transport
	{
		void push(PushRequest request, String accessToken, Consumer<PushResult> done);
	}

	public interface Listener
	{
		void pusherChanged(Pusher pusher);
	}

	private final StateStore store;
	private final Transport transport;
	private final Credentials credentials;
	private final Scheduler scheduler;
	private final LongSupplier clock;
	private final String clientId;
	private final PluginBuild build;
	private volatile Listener listener = p -> {};

	private boolean enabled;
	/** The character being pushed; null while logged out (after the logout push). */
	private CharacterRef character;
	/** Account hash and profile of the last character pushed, to tell a relog from another one. */
	private String identity;
	private boolean inFlight;
	private long seq;
	private Scheduler.Task timer;
	private long timerAt;
	private boolean backingOff;
	private long backoffMs;
	private boolean retriedAuth;
	private long heartbeatMs = DEFAULT_HEARTBEAT_MS;
	private List<String> wanted = Collections.emptyList();
	private long lastAckAt;
	private String problem;

	public Pusher(StateStore store, Transport transport, Credentials credentials, Scheduler scheduler,
		LongSupplier clock, String clientId, PluginBuild build)
	{
		this.store = store;
		this.transport = transport;
		this.credentials = credentials;
		this.scheduler = scheduler;
		this.clock = clock;
		this.clientId = clientId;
		this.build = build;
	}

	public void setListener(Listener listener)
	{
		this.listener = listener != null ? listener : p -> {};
	}

	/** Linked and switched on. Off stops all pushing; changes keep being recorded. */
	public synchronized void setEnabled(boolean on)
	{
		if (enabled == on)
		{
			return;
		}
		enabled = on;
		backoffMs = 0;
		retriedAuth = false;
		cancelTimer();
		if (on && character != null)
		{
			schedule(0);
		}
		if (!on)
		{
			problem = null;
		}
		notifyListener();
	}

	/**
	 * A tracked character is logged in. Another character than last time starts from an empty
	 * store, since its values aren't ours to keep.
	 */
	public synchronized void loggedIn(CharacterRef ref)
	{
		String who = ref.getAccountHash() + "/" + ref.getProfile();
		if (!who.equals(identity))
		{
			store.reset();
			identity = who;
		}
		character = ref.toBuilder().setLoggedIn(true).build();
		if (enabled && !backingOff)
		{
			schedule(0);
		}
		notifyListener();
	}

	/** Back at the login screen, or on an untracked world: one push says so, then nothing. */
	public synchronized void loggedOut()
	{
		if (character == null || !character.getLoggedIn())
		{
			return;
		}
		character = character.toBuilder().setLoggedIn(false).build();
		if (enabled && !backingOff)
		{
			cancelTimer();
			schedule(0);
		}
		notifyListener();
	}

	/** The store has changes. */
	public synchronized void changed()
	{
		if (enabled && character != null && character.getLoggedIn() && !backingOff)
		{
			schedule(THROTTLE_MS);
		}
	}

	/** Fields the relay wants built while they're named (combat_achievements, collection_log). */
	public synchronized List<String> wanted()
	{
		return wanted;
	}

	public synchronized long lastAckAt()
	{
		return lastAckAt;
	}

	public synchronized String problem()
	{
		return problem;
	}

	public synchronized CharacterRef character()
	{
		return character;
	}

	public synchronized void shutDown()
	{
		enabled = false;
		cancelTimer();
	}

	private void send()
	{
		PushRequest request;
		StateStore.Snapshot snapshot;
		synchronized (this)
		{
			timer = null;
			backingOff = false;
			if (!enabled || character == null || inFlight)
			{
				return;
			}
			// State may only go with a logged-in character; a logout push is presence alone.
			snapshot = character.getLoggedIn() ? store.snapshot() : null;
			long now = clock.getAsLong();
			seq = Math.max(seq + 1, now);
			PushRequest.Builder push = PushRequest.newBuilder()
				.setClientId(clientId)
				.setPlugin(build)
				.setCharacter(character)
				.setSeq(seq)
				.setCapturedAtMs(now);
			if (snapshot != null && !snapshot.isEmpty())
			{
				push.setState(snapshot.state).setUpdateMask(snapshot.mask);
			}
			request = push.build();
			inFlight = true;
		}

		credentials.accessToken(token ->
		{
			if (token.token != null)
			{
				transport.push(request, token.token, result -> onResult(request, snapshot, result));
			}
			else
			{
				onNoToken(token);
			}
		});
	}

	private void onNoToken(Link.AccessToken token)
	{
		synchronized (this)
		{
			inFlight = false;
			if (token.unlinked)
			{
				// The link is over; the plugin turns pushing off when it hears.
				problem = "Not linked";
				cancelTimer();
			}
			else
			{
				problem = "Can't reach Gielinor's account service";
				backOff(0);
			}
		}
		notifyListener();
	}

	private void onResult(PushRequest sent, StateStore.Snapshot snapshot, PushResult result)
	{
		boolean expire = false;
		synchronized (this)
		{
			inFlight = false;
			switch (result.status)
			{
				case OK:
					onAcknowledged(sent, snapshot, result);
					break;
				case UNAUTHENTICATED:
					credentials.invalidateAccessToken();
					if (!retriedAuth)
					{
						// Most likely an access token that ran out early: one retry with a new one.
						retriedAuth = true;
						schedule(0);
					}
					else
					{
						problem = "Gielinor didn't accept the link: " + result.message;
						backOff(result.retryAfterMs);
					}
					break;
				case PERMISSION_DENIED:
					problem = "This link can't send game state. Link again.";
					cancelTimer();
					expire = true;
					break;
				case FAILED_PRECONDITION:
					if (result.lastSeq != null)
					{
						// Another push (an earlier run, a delayed retry) got further: go on from there.
						seq = Math.max(seq, result.lastSeq);
						schedule(0);
					}
					else
					{
						backOff(result.retryAfterMs);
					}
					break;
				case INVALID_ARGUMENT:
					// A bug on our side. Drop what was sent rather than repeat it forever.
					log.warn("Gielinor: the relay refused a push ({}): paths {}", result.message,
						sent.getUpdateMask().getPathsList());
					if (snapshot != null)
					{
						store.acknowledged(snapshot);
					}
					problem = "Gielinor refused some data: " + result.message;
					backOff(result.retryAfterMs);
					break;
				default:
					problem = describe(result);
					backOff(result.retryAfterMs);
					break;
			}
		}
		if (expire)
		{
			credentials.expire("The relay refused this link's token.");
		}
		notifyListener();
	}

	private void onAcknowledged(PushRequest sent, StateStore.Snapshot snapshot, PushResult result)
	{
		retriedAuth = false;
		backoffMs = 0;
		problem = null;
		lastAckAt = clock.getAsLong();
		if (snapshot != null)
		{
			store.acknowledged(snapshot);
		}
		if (result.response.hasMissing())
		{
			store.markMissing(result.response.getMissing().getPathsList());
		}
		wanted = result.response.hasWanted()
			? Collections.unmodifiableList(result.response.getWanted().getPathsList())
			: Collections.emptyList();
		int heartbeatS = result.response.getNextHeartbeatS();
		heartbeatMs = heartbeatS > 0 ? heartbeatS * 1000L : DEFAULT_HEARTBEAT_MS;

		if (!sent.getCharacter().getLoggedIn() && !character.getLoggedIn())
		{
			// The logout push landed: stop until the next login.
			character = null;
			cancelTimer();
			return;
		}
		cancelTimer();
		if (character.getLoggedIn() != sent.getCharacter().getLoggedIn())
		{
			// Logged in or out while this was in flight: say so now.
			schedule(0);
		}
		else
		{
			schedule(store.hasChanges() ? THROTTLE_MS : heartbeatMs);
		}
	}

	private static String describe(PushResult result)
	{
		switch (result.status)
		{
			case NETWORK:
				return "Can't reach Gielinor. Retrying.";
			case RESOURCE_EXHAUSTED:
				return "Gielinor asked to slow down. Retrying.";
			case UNAVAILABLE:
				return "Gielinor is unavailable. Retrying.";
			default:
				return "Gielinor answered with an error: " + result.message;
		}
	}

	/** Waits longer each time, at least as long as the relay asked. */
	private void backOff(long retryAfterMs)
	{
		backoffMs = backoffMs == 0 ? MIN_BACKOFF_MS : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
		cancelTimer();
		schedule(Math.max(backoffMs, retryAfterMs));
		backingOff = true;
	}

	/** Runs send() after delayMs, unless it is already due sooner. */
	private void schedule(long delayMs)
	{
		long at = clock.getAsLong() + delayMs;
		if (timer != null && timerAt <= at)
		{
			return;
		}
		cancelTimer();
		timerAt = at;
		timer = scheduler.schedule(this::send, delayMs);
	}

	private void cancelTimer()
	{
		if (timer != null)
		{
			timer.cancel();
			timer = null;
		}
		backingOff = false;
	}

	private void notifyListener()
	{
		listener.pusherChanged(this);
	}
}
