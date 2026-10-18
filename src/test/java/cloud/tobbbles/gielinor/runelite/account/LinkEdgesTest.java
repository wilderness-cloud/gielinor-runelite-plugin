package cloud.tobbbles.gielinor.runelite.account;

import cloud.tobbbles.gielinor.runelite.FakeHttp;
import cloud.tobbbles.gielinor.runelite.FakeScheduler;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/** The link's less travelled paths: odd answers, and calls made in the wrong state. */
public class LinkEdgesTest
{
	private static final String ISSUER = "https://gielinor.test";
	private static final String DISCOVERY = "{\"issuer\":\"https://gielinor.test\","
		+ "\"device_authorization_endpoint\":\"https://gielinor.test/code\","
		+ "\"token_endpoint\":\"https://gielinor.test/token\","
		+ "\"revocation_endpoint\":\"https://gielinor.test/revoke\"}";

	private FakeHttp http;
	private FakeScheduler scheduler;
	private MemoryTokenStore store;

	@Before
	public void setUp()
	{
		http = new FakeHttp();
		scheduler = new FakeScheduler();
		store = new MemoryTokenStore();
	}

	private Link link()
	{
		return new Link(new AccountClient(http.client, new Gson(), ISSUER), store, scheduler, scheduler::now);
	}

	private Link linked()
	{
		store.save(ISSUER, "refresh-1");
		return link();
	}

	private static String code(int expiresIn, int interval)
	{
		return "{\"device_code\":\"dev-1\",\"user_code\":\"ABCD\",\"verification_uri\":\"https://gielinor.test/device\","
			+ "\"expires_in\":" + expiresIn + ",\"interval\":" + interval + "}";
	}

	private static List<Link.AccessToken> tokens(Link link)
	{
		List<Link.AccessToken> got = new ArrayList<>();
		link.accessToken(got::add);
		return got;
	}

	@Test
	public void startingWhileLinkedOrLinkingDoesNothing()
	{
		Link link = linked();
		link.start();
		assertEquals(0, http.requests().size());

		store.clear();
		link = link();
		http.answer(200, DISCOVERY).answer(200, code(900, 5));
		link.start();
		link.start();
		assertEquals(2, http.requests().size());
	}

	@Test
	public void cancellingWhenNotLinkingDoesNothing()
	{
		Link link = linked();
		link.cancel();
		assertEquals(Link.State.LINKED, link.state());
	}

	@Test
	public void aCodeWithoutADeviceCodeIsAProblem()
	{
		http.answer(200, DISCOVERY).answer(200, "{\"user_code\":\"ABCD\"}");
		Link link = link();
		link.start();
		assertEquals(Link.State.UNLINKED, link.state());
		assertEquals("Couldn't start linking: unexpected answer", link.problem());
	}

	@Test
	public void aCodeWithoutAnIntervalPollsEveryFiveSeconds()
	{
		http.answer(200, DISCOVERY).answer(200, code(0, 0));
		Link link = link();
		link.start();
		assertEquals(5_000, scheduler.nextDelay());

		// A code that claims to expire at once still gets a minute.
		for (int i = 0; i < 11; i++)
		{
			http.answer(400, "{\"error\":\"authorization_pending\"}");
			scheduler.advance(5_000);
		}
		assertEquals(Link.State.LINKING, link.state());
		scheduler.advance(5_000);
		assertEquals(Link.State.UNLINKED, link.state());
		assertEquals("The code expired before it was approved. Try again.", link.problem());
	}

	@Test
	public void tokensWithoutARefreshTokenArentALink()
	{
		http.answer(200, DISCOVERY).answer(200, code(900, 5)).answer(200, "{\"access_token\":\"a\"}");
		Link link = link();
		link.start();
		scheduler.advance(5_000);
		assertEquals(Link.State.UNLINKED, link.state());
		assertEquals("The account service didn't grant a lasting link.", link.problem());
		assertNull(store.refreshToken());
	}

	@Test
	public void theCodeCanRunOutAtTheServer()
	{
		http.answer(200, DISCOVERY).answer(200, code(900, 5)).answer(400, "{\"error\":\"expired_token\"}");
		Link link = link();
		link.start();
		scheduler.advance(5_000);
		assertEquals(Link.State.UNLINKED, link.state());
		assertTrue(link.problem().contains("expired"));
	}

	@Test
	public void anAnswerAfterCancellingIsIgnored()
	{
		http.answer(200, DISCOVERY).answer(200, code(900, 5));
		Link link = link();
		link.start();
		http.answer(request ->
		{
			link.cancel();
			return FakeHttp.response(request, 200, okhttp3.ResponseBody.create(FakeHttp.JSON,
				"{\"access_token\":\"a\",\"refresh_token\":\"r\"}"));
		});
		scheduler.advance(5_000);
		assertEquals(Link.State.UNLINKED, link.state());
		assertNull(store.refreshToken());
	}

	@Test
	public void aRefreshWithoutARotatedTokenKeepsTheOldOneAndDefaultsItsLifetime()
	{
		Link link = linked();
		http.answer(200, DISCOVERY).answer(200, "{\"access_token\":\"access-2\"}");
		assertEquals("access-2", tokens(link).get(0).token);
		assertEquals("refresh-1", store.refreshToken());

		scheduler.advance(3_600_000 - 61_000);
		assertEquals("still fresh: no request", 2, http.requests().size());
		assertEquals("access-2", tokens(link).get(0).token);
	}

	@Test
	public void aRefreshAnsweredAfterUnlinkingIsUnlinked()
	{
		Link link = linked();
		List<Link.AccessToken> got = new ArrayList<>();
		http.answer(200, DISCOVERY).answer(request ->
		{
			link.unlink();
			return FakeHttp.response(request, 200, okhttp3.ResponseBody.create(FakeHttp.JSON, "{\"access_token\":\"a\"}"));
		});
		// The unlink's revoke fails: nothing queued for it. Unlinking goes ahead anyway.
		link.accessToken(got::add);
		assertTrue(got.get(got.size() - 1).unlinked);
		assertEquals(Link.State.UNLINKED, link.state());
	}

	@Test
	public void aRevokeThatFailsStillUnlinks()
	{
		Link link = linked();
		http.answer(200, DISCOVERY).answer(500, "{}");
		link.unlink();
		assertEquals(Link.State.UNLINKED, link.state());
		assertNull(store.refreshToken());

		link.unlink();
		assertEquals("nothing to revoke the second time", 2, http.requests().size());
	}

	@Test
	public void theRelayCanOnlyExpireALiveLink()
	{
		Link link = link();
		link.expire("why");
		assertEquals(Link.State.UNLINKED, link.state());
		assertNull(link.problem());
	}

	@Test
	public void aStoredTokenWithNoIssuerIsntALink()
	{
		store.save(null, "refresh-1");
		assertEquals(Link.State.UNLINKED, link().state());
	}
}
