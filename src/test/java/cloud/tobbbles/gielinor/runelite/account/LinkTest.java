package cloud.tobbbles.gielinor.runelite.account;

import cloud.tobbbles.gielinor.runelite.FakeHttp;
import cloud.tobbbles.gielinor.runelite.FakeScheduler;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class LinkTest
{
	private static final String ISSUER = "https://gielinor.test";
	private static final String DISCOVERY = "{"
		+ "\"issuer\":\"https://gielinor.test\","
		+ "\"device_authorization_endpoint\":\"https://gielinor.test/api/auth/device/code\","
		+ "\"token_endpoint\":\"https://gielinor.test/api/auth/oauth2/token\","
		+ "\"revocation_endpoint\":\"https://gielinor.test/api/auth/oauth2/revoke\"}";
	private static final String CODE = "{\"device_code\":\"dev-1\",\"user_code\":\"BDWP-HQPK\","
		+ "\"verification_uri\":\"https://gielinor.test/device\","
		+ "\"verification_uri_complete\":\"https://gielinor.test/device?user_code=BDWP-HQPK\","
		+ "\"expires_in\":900,\"interval\":5}";

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
		return new Link(new AccountClient(http.client, new Gson(), ISSUER + "/"), store, scheduler, scheduler::now);
	}

	private static String tokens(String access, String refresh)
	{
		return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
			+ "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"runelite:push offline_access\"}";
	}

	private static String error(String code)
	{
		return "{\"error\":\"" + code + "\"}";
	}

	@Test
	public void linksThroughTheDeviceGrant()
	{
		http.answer(200, DISCOVERY).answer(200, CODE);
		Link link = link();
		List<Link.State> seen = new ArrayList<>();
		link.addListener(l -> seen.add(l.state()));

		link.start();

		assertEquals(Link.State.LINKING, link.state());
		assertEquals("BDWP-HQPK", link.code().user_code);
		assertEquals("https://gielinor.test/api/auth/device/code", http.last().url().toString());
		assertTrue(http.lastForm().contains("client_id=gielinor-runelite"));
		assertTrue(http.lastForm().contains("scope=runelite%3Apush%20offline_access"));
		assertEquals(5_000, scheduler.nextDelay());

		http.answer(400, error("authorization_pending"));
		scheduler.advance(5_000);
		assertEquals(Link.State.LINKING, link.state());
		assertTrue(http.lastForm().contains("device_code=dev-1"));
		assertTrue(http.lastForm().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));

		http.answer(200, tokens("access-1", "refresh-1"));
		scheduler.advance(5_000);

		assertEquals(Link.State.LINKED, link.state());
		assertNull(link.code());
		assertEquals("refresh-1", store.refreshToken());
		assertEquals(ISSUER, store.issuer());
		assertEquals(Link.State.LINKED, seen.get(seen.size() - 1));
		assertEquals(0, scheduler.scheduled());

		// The access token from linking is used without another request.
		int before = http.requests().size();
		List<Link.AccessToken> got = new ArrayList<>();
		link.accessToken(got::add);
		assertEquals("access-1", got.get(0).token);
		assertEquals(before, http.requests().size());
	}

	@Test
	public void slowsDownByFiveSecondsWhenAsked()
	{
		http.answer(200, DISCOVERY).answer(200, CODE).answer(400, error("slow_down"));
		Link link = link();
		link.start();
		scheduler.advance(5_000);

		assertEquals(Link.State.LINKING, link.state());
		assertEquals(10_000, scheduler.nextDelay());
	}

	@Test
	public void stopsWhenThePlayerDeclines()
	{
		http.answer(200, DISCOVERY).answer(200, CODE).answer(400, error("access_denied"));
		Link link = link();
		link.start();
		scheduler.advance(5_000);
		scheduler.runDue();

		assertEquals(Link.State.UNLINKED, link.state());
		assertEquals("Linking was declined.", link.problem());
		assertNull(store.refreshToken());
		assertEquals(0, scheduler.scheduled());
	}

	@Test
	public void keepsPollingThroughDroppedConnectionsUntilTheCodeRunsOut()
	{
		http.answer(200, DISCOVERY).answer(200, CODE);
		Link link = link();
		link.start();
		for (int i = 0; i < 179; i++)
		{
			http.fail();
		}
		scheduler.advance(895_000);
		assertEquals(Link.State.LINKING, link.state());

		scheduler.advance(10_000);
		scheduler.runDue();
		assertEquals(Link.State.UNLINKED, link.state());
		assertTrue(link.problem().contains("expired"));
	}

	@Test
	public void cancellingDropsTheCode()
	{
		http.answer(200, DISCOVERY).answer(200, CODE);
		Link link = link();
		link.start();
		link.cancel();

		assertEquals(Link.State.UNLINKED, link.state());
		assertEquals(0, scheduler.scheduled());
	}

	@Test
	public void reportsAServerWithoutDeviceSignIn()
	{
		http.answer(200, "{\"issuer\":\"https://gielinor.test\"}");
		Link link = link();
		link.start();

		assertEquals(Link.State.UNLINKED, link.state());
		assertTrue(link.problem().contains("doesn't offer device sign-in"));
	}

	@Test
	public void aStoredTokenIsLinkedOnlyForItsOwnServer()
	{
		store.save(ISSUER, "refresh-1");
		assertEquals(Link.State.LINKED, link().state());

		store.save("https://elsewhere.test", "refresh-1");
		assertEquals(Link.State.UNLINKED, link().state());
	}

	@Test
	public void refreshesOnceForEveryoneWaitingAndKeepsTheRotatedToken()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		List<Link.AccessToken> got = new ArrayList<>();

		// The discovery and refresh answers are queued after both callers ask: with an
		// inline dispatcher the first call would otherwise finish before the second asks.
		http.answer(request ->
		{
			// Second caller asks while the first refresh is out.
			link.accessToken(got::add);
			return FakeHttp.response(request, 200, okhttp3.ResponseBody.create(FakeHttp.JSON, DISCOVERY));
		});
		http.answer(200, tokens("access-2", "refresh-2"));
		link.accessToken(got::add);

		assertEquals(2, got.size());
		assertEquals("access-2", got.get(0).token);
		assertEquals("access-2", got.get(1).token);
		assertEquals(2, http.requests().size());
		assertTrue(http.lastForm().contains("grant_type=refresh_token"));
		assertTrue(http.lastForm().contains("refresh_token=refresh-1"));
		assertEquals("refresh-2", store.refreshToken());
	}

	@Test
	public void refreshesAMinuteBeforeTheTokenRunsOut()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		http.answer(200, DISCOVERY).answer(200, tokens("access-2", "refresh-2"));
		List<Link.AccessToken> got = new ArrayList<>();
		link.accessToken(got::add);

		scheduler.advance(3_600_000 - Link.EXPIRY_MARGIN_MS - 1);
		link.accessToken(got::add);
		assertEquals("access-2", got.get(1).token);

		http.answer(200, tokens("access-3", "refresh-3"));
		scheduler.advance(1);
		link.accessToken(got::add);
		assertEquals("access-3", got.get(2).token);
	}

	@Test
	public void aRefusedRefreshEndsTheLink()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		http.answer(200, DISCOVERY).answer(400, error("invalid_grant"));
		List<Link.AccessToken> got = new ArrayList<>();

		link.accessToken(got::add);

		assertTrue(got.get(0).unlinked);
		assertEquals(Link.State.EXPIRED, link.state());
		assertNull(store.refreshToken());
		assertNotNull(link.problem());
	}

	@Test
	public void aFailedRefreshIsRetryableAndKeepsTheLink()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		http.answer(200, DISCOVERY).answer(503, "{}");
		List<Link.AccessToken> got = new ArrayList<>();

		link.accessToken(got::add);

		assertTrue(got.get(0).retryable());
		assertEquals(Link.State.LINKED, link.state());
		assertEquals("refresh-1", store.refreshToken());
	}

	@Test
	public void unlinkingRevokesAndForgets()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		http.answer(200, DISCOVERY).answer(200, "");

		link.unlink();

		assertEquals(Link.State.UNLINKED, link.state());
		assertNull(store.refreshToken());
		assertEquals("https://gielinor.test/api/auth/oauth2/revoke", http.last().url().toString());
		assertTrue(http.lastForm().contains("token=refresh-1"));
		assertTrue(http.lastForm().contains("token_type_hint=refresh_token"));

		List<Link.AccessToken> got = new ArrayList<>();
		link.accessToken(got::add);
		assertTrue(got.get(0).unlinked);
	}

	@Test
	public void theRelayCanExpireTheLink()
	{
		store.save(ISSUER, "refresh-1");
		Link link = link();
		link.expire("wrong scope");

		assertEquals(Link.State.EXPIRED, link.state());
		assertNull(store.refreshToken());
		assertFalse(link.problem().isEmpty());
	}
}
