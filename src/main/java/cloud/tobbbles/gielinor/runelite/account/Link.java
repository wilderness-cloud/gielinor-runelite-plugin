package cloud.tobbbles.gielinor.runelite.account;

import cloud.tobbbles.gielinor.runelite.Scheduler;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.DeviceCode;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.Outcome;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.Result;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The plugin's link to a Gielinor account.
 *
 * <ul>
 * <li>{@link State#UNLINKED}: nothing is stored and nothing is sent.</li>
 * <li>{@link State#LINKING}: a device code is out; the player approves it in their browser while
 * this polls (RFC 8628).</li>
 * <li>{@link State#LINKED}: a refresh token is stored. The access token lives only in memory and
 * is refreshed when it runs out, one refresh at a time however many callers ask.</li>
 * <li>{@link State#EXPIRED}: the account service refused the refresh token (unlinked from the
 * site, past 90 days, or replayed). The stored token is gone; the player links again.</li>
 * </ul>
 */
@Slf4j
public class Link implements Credentials
{
	/** Refresh this long before the access token's stated expiry. */
	static final long EXPIRY_MARGIN_MS = 60_000;
	static final long SLOW_DOWN_STEP_MS = 5_000;

	public enum State
	{
		UNLINKED, LINKING, LINKED, EXPIRED
	}

	public interface Listener
	{
		void linkChanged(Link link);
	}

	/** What a caller asking for an access token gets. */
	public static final class AccessToken
	{
		/** The token, or null. */
		public final String token;
		/** The link is over (or never was): stop until the player links again. */
		public final boolean unlinked;

		private AccessToken(String token, boolean unlinked)
		{
			this.token = token;
			this.unlinked = unlinked;
		}

		public static AccessToken of(String token)
		{
			return new AccessToken(token, false);
		}

		/** Not linked, or no longer. */
		public static AccessToken unlinked()
		{
			return new AccessToken(null, true);
		}

		/** Linked, but no token right now (the account service can't be reached). */
		public static AccessToken unavailable()
		{
			return new AccessToken(null, false);
		}

		/** No token this time, but the link stands: try again later. */
		public boolean retryable()
		{
			return token == null && !unlinked;
		}
	}

	private final AccountClient account;
	private final TokenStore store;
	private final Scheduler scheduler;
	private final LongSupplier clock;
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private State state;
	private String problem;
	private DeviceCode code;
	private long codeExpiresAt;
	private long pollIntervalMs;
	private Scheduler.Task poll;
	private String accessToken;
	private long accessExpiresAt;
	private boolean refreshing;
	private List<Consumer<AccessToken>> waiting = new ArrayList<>();

	public Link(AccountClient account, TokenStore store, Scheduler scheduler, LongSupplier clock)
	{
		this.account = account;
		this.store = store;
		this.scheduler = scheduler;
		this.clock = clock;
		boolean stored = store.refreshToken() != null && account.issuer().equals(store.issuer());
		this.state = stored ? State.LINKED : State.UNLINKED;
	}

	public void addListener(Listener listener)
	{
		listeners.add(listener);
	}

	public synchronized State state()
	{
		return state;
	}

	/** The device code to show while linking, or null. */
	public synchronized DeviceCode code()
	{
		return state == State.LINKING ? code : null;
	}

	/** Why the last attempt went wrong, for the panel; null when nothing did. */
	public synchronized String problem()
	{
		return problem;
	}

	/** Asks for a device code and starts polling. Does nothing while already linking or linked. */
	public void start()
	{
		synchronized (this)
		{
			if (state == State.LINKING || state == State.LINKED)
			{
				return;
			}
			problem = null;
		}
		changed();
		account.requestDeviceCode(this::onCode);
	}

	/** Stops linking: the code is dropped and nothing is stored. */
	public void cancel()
	{
		synchronized (this)
		{
			if (state != State.LINKING)
			{
				return;
			}
			cancelPoll();
			code = null;
			state = State.UNLINKED;
		}
		changed();
	}

	/** Forgets the link here and revokes it on the account service (best effort). */
	public void unlink()
	{
		String refresh;
		List<Consumer<AccessToken>> waiters;
		synchronized (this)
		{
			cancelPoll();
			refresh = store.refreshToken();
			store.clear();
			code = null;
			accessToken = null;
			problem = null;
			state = State.UNLINKED;
			waiters = takeWaiters();
		}
		if (refresh != null)
		{
			account.revoke(refresh, r ->
			{
				if (r.outcome != Outcome.OK)
				{
					log.debug("Gielinor: revoking the refresh token failed: {}", r.detail);
				}
			});
		}
		answer(waiters, AccessToken.unlinked());
		changed();
	}

	/**
	 * Calls back with a current access token, refreshing first if it has run out. The callback
	 * may run on this thread or an OkHttp one.
	 */
	@Override
	public void accessToken(Consumer<AccessToken> callback)
	{
		// Callbacks always run outside this object's lock, so a caller holding a lock of its own
		// can't deadlock against a refresh answering on an OkHttp thread.
		AccessToken now = null;
		String refresh = null;
		boolean startRefresh = false;
		synchronized (this)
		{
			if (state != State.LINKED)
			{
				now = AccessToken.unlinked();
			}
			else if (accessToken != null && clock.getAsLong() < accessExpiresAt - EXPIRY_MARGIN_MS)
			{
				now = AccessToken.of(accessToken);
			}
			else
			{
				waiting.add(callback);
				if (!refreshing)
				{
					refreshing = true;
					startRefresh = true;
					refresh = store.refreshToken();
				}
			}
		}
		if (now != null)
		{
			callback.accept(now);
		}
		else if (startRefresh)
		{
			if (refresh == null)
			{
				// Linked with nothing stored (the config was edited): the link is over.
				onRefresh(Result.of(Outcome.INVALID_GRANT));
			}
			else
			{
				account.refresh(refresh, this::onRefresh);
			}
		}
	}

	/** The relay refused the current access token: the next caller gets a fresh one. */
	@Override
	public synchronized void invalidateAccessToken()
	{
		accessToken = null;
	}

	/** The relay says this token may not push at all: end the link, as an expired refresh does. */
	@Override
	public void expire(String why)
	{
		List<Consumer<AccessToken>> waiters;
		synchronized (this)
		{
			if (state != State.LINKED)
			{
				return;
			}
			waiters = expireLocked(why);
		}
		answer(waiters, AccessToken.unlinked());
		changed();
	}

	private void onCode(Result<DeviceCode> result)
	{
		synchronized (this)
		{
			if (state == State.LINKED || state == State.LINKING)
			{
				return;
			}
			if (result.outcome != Outcome.OK || result.value == null || result.value.device_code == null)
			{
				problem = "Couldn't start linking: " + (result.detail != null ? result.detail : "unexpected answer");
			}
			else
			{
				code = result.value;
				long now = clock.getAsLong();
				codeExpiresAt = now + Math.max(60, code.expires_in) * 1000L;
				pollIntervalMs = Math.max(1, code.interval > 0 ? code.interval : 5) * 1000L;
				state = State.LINKING;
				schedulePoll();
			}
		}
		changed();
	}

	private void poll()
	{
		String deviceCode;
		synchronized (this)
		{
			poll = null;
			if (state != State.LINKING)
			{
				return;
			}
			if (clock.getAsLong() >= codeExpiresAt)
			{
				giveUpLinking("The code expired before it was approved. Try again.");
				return;
			}
			deviceCode = code.device_code;
		}
		account.pollDeviceToken(deviceCode, this::onPoll);
	}

	private void onPoll(Result<Tokens> result)
	{
		synchronized (this)
		{
			if (state != State.LINKING)
			{
				return;
			}
			switch (result.outcome)
			{
				case OK:
					if (result.value == null || result.value.refresh_token == null)
					{
						giveUpLinking("The account service didn't grant a lasting link.");
						return;
					}
					cancelPoll();
					store.save(account.issuer(), result.value.refresh_token);
					keepAccessToken(result.value);
					code = null;
					problem = null;
					state = State.LINKED;
					break;
				case SLOW_DOWN:
					// RFC 8628 §3.5: add five seconds, for good.
					pollIntervalMs += SLOW_DOWN_STEP_MS;
					schedulePoll();
					return;
				case DENIED:
					giveUpLinking("Linking was declined.");
					return;
				case EXPIRED:
					giveUpLinking("The code expired before it was approved. Try again.");
					return;
				default:
					// Pending, or a dropped connection: keep going until the code runs out.
					schedulePoll();
					return;
			}
		}
		changed();
	}

	private void onRefresh(Result<Tokens> result)
	{
		List<Consumer<AccessToken>> waiters;
		AccessToken answer;
		boolean stateChanged = false;
		synchronized (this)
		{
			refreshing = false;
			if (state != State.LINKED)
			{
				waiters = takeWaiters();
				answer = AccessToken.unlinked();
			}
			else if (result.outcome == Outcome.OK && result.value != null && result.value.access_token != null)
			{
				if (result.value.refresh_token != null)
				{
					// Refresh tokens rotate: the old one is spent.
					store.save(account.issuer(), result.value.refresh_token);
				}
				keepAccessToken(result.value);
				waiters = takeWaiters();
				answer = AccessToken.of(accessToken);
			}
			else if (result.outcome == Outcome.INVALID_GRANT)
			{
				waiters = expireLocked("The link has expired or was removed. Link again to keep sending.");
				answer = AccessToken.unlinked();
				stateChanged = true;
			}
			else
			{
				log.debug("Gielinor: refreshing the access token failed: {}", result.detail);
				waiters = takeWaiters();
				answer = AccessToken.unavailable();
			}
		}
		answer(waiters, answer);
		if (stateChanged)
		{
			changed();
		}
	}

	private List<Consumer<AccessToken>> expireLocked(String why)
	{
		store.clear();
		accessToken = null;
		problem = why;
		state = State.EXPIRED;
		return takeWaiters();
	}

	private void keepAccessToken(Tokens tokens)
	{
		accessToken = tokens.access_token;
		long lifetimeMs = (tokens.expires_in > 0 ? tokens.expires_in : 3600) * 1000L;
		accessExpiresAt = clock.getAsLong() + lifetimeMs;
	}

	private void giveUpLinking(String why)
	{
		cancelPoll();
		code = null;
		problem = why;
		state = State.UNLINKED;
		// Called with the lock held; tell listeners once it is released.
		scheduler.schedule(this::changed, 0);
	}

	private void schedulePoll()
	{
		cancelPoll();
		poll = scheduler.schedule(this::poll, pollIntervalMs);
	}

	private void cancelPoll()
	{
		if (poll != null)
		{
			poll.cancel();
			poll = null;
		}
	}

	private List<Consumer<AccessToken>> takeWaiters()
	{
		List<Consumer<AccessToken>> taken = waiting;
		waiting = new ArrayList<>();
		return taken;
	}

	private static void answer(List<Consumer<AccessToken>> waiters, AccessToken token)
	{
		for (Consumer<AccessToken> waiter : waiters)
		{
			waiter.accept(token);
		}
	}

	private void changed()
	{
		for (Listener listener : listeners)
		{
			listener.linkChanged(this);
		}
	}
}
