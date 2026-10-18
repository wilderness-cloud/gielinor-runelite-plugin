package cloud.tobbbles.gielinor.runelite.account;

import cloud.tobbbles.gielinor.runelite.FakeHttp;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.Outcome;
import cloud.tobbbles.gielinor.runelite.account.AccountClient.Result;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The account client's failures: each becomes a Result the link can act on, never an exception. */
public class AccountClientTest
{
	private static final String DISCOVERY = "{\"issuer\":\"https://gielinor.test\","
		+ "\"device_authorization_endpoint\":\"https://gielinor.test/code\","
		+ "\"token_endpoint\":\"https://gielinor.test/token\"}";

	private final FakeHttp http = new FakeHttp();
	private final AccountClient account = new AccountClient(http.client, new Gson(), " https://gielinor.test/ ");

	private <T> Result<T> one(java.util.function.Consumer<java.util.function.Consumer<Result<T>>> call)
	{
		List<Result<T>> results = new ArrayList<>();
		call.accept(results::add);
		assertEquals(1, results.size());
		return results.get(0);
	}

	@Test
	public void theIssuerIsTrimmed()
	{
		assertEquals("https://gielinor.test", account.issuer());
		assertEquals("", new AccountClient(http.client, new Gson(), null).issuer());
	}

	@Test
	public void aServerAddressThatIsntAUrlFails()
	{
		AccountClient bad = new AccountClient(http.client, new Gson(), "not a url");
		Result<AccountClient.DeviceCode> r = one(bad::requestDeviceCode);
		assertEquals(Outcome.FAILED, r.outcome);
		assertTrue(r.detail.contains("isn't a URL"));
	}

	@Test
	public void discoveryFailures()
	{
		http.fail();
		assertTrue(one(account::requestDeviceCode).detail.startsWith("can't reach"));

		http.answer(404, "{}");
		assertTrue(one(account::requestDeviceCode).detail.contains("doesn't offer device sign-in"));

		http.answer(200, "not json {");
		assertTrue(one(account::requestDeviceCode).detail.contains("doesn't offer device sign-in"));
	}

	@Test
	public void aBadAdvertisedEndpointFails()
	{
		http.answer(200, "{\"device_authorization_endpoint\":\"nope\",\"token_endpoint\":\"nope\"}");
		assertTrue(one(account::requestDeviceCode).detail.contains("bad URL"));
	}

	@Test
	public void endpointFailures()
	{
		http.answer(200, DISCOVERY).fail();
		assertTrue(one(account::requestDeviceCode).detail.startsWith("can't reach"));

		// Discovery is remembered from here on.
		http.answer(200, "[1,2]");
		assertTrue(one(account::requestDeviceCode).detail.contains("can't read"));

		http.answer(200, "");
		assertTrue(one(account::requestDeviceCode).detail.contains("can't read"));

		http.answer(500, "<html>");
		assertEquals("the account service answered 500", one(account::requestDeviceCode).detail);

		http.answer(400, "{\"error\":\"invalid_scope\"}");
		assertEquals("the account service answered 400 (invalid_scope)", one(account::requestDeviceCode).detail);
	}

	@Test
	public void pollAnswers()
	{
		http.answer(200, DISCOVERY).answer(400, "{\"error\":\"expired_token\"}");
		assertEquals(Outcome.EXPIRED, this.<AccountClient.Tokens>one(d -> account.pollDeviceToken("dev", d)).outcome);
		http.answer(400, "{\"error\":\"invalid_client\"}");
		assertEquals(Outcome.FAILED, this.<AccountClient.Tokens>one(d -> account.pollDeviceToken("dev", d)).outcome);
		http.answer(400, "{}");
		assertEquals(Outcome.FAILED, this.<AccountClient.Tokens>one(d -> account.pollDeviceToken("dev", d)).outcome);
	}

	@Test
	public void refreshAnswers()
	{
		http.answer(200, DISCOVERY).answer(401, "{\"error\":\"unauthorized_client\"}");
		assertEquals(Outcome.INVALID_GRANT, this.<AccountClient.Tokens>one(d -> account.refresh("r", d)).outcome);
		http.answer(400, "{\"error\":\"invalid_client\"}");
		assertEquals(Outcome.INVALID_GRANT, this.<AccountClient.Tokens>one(d -> account.refresh("r", d)).outcome);
		http.answer(503, "{\"error\":\"temporarily_unavailable\"}");
		assertEquals(Outcome.FAILED, this.<AccountClient.Tokens>one(d -> account.refresh("r", d)).outcome);
	}

	@Test
	public void revokingNeedsARevocationEndpoint()
	{
		http.answer(200, DISCOVERY);
		Result<Void> r = this.<Void>one(d -> account.revoke("r", d));
		assertEquals(Outcome.FAILED, r.outcome);
		assertTrue(r.detail.contains("no revocation endpoint"));
	}

	@Test
	public void aRevokeNeedsNoBody()
	{
		http.answer(200, DISCOVERY.replace("}", ",\"revocation_endpoint\":\"https://gielinor.test/revoke\"}"))
			.answer(request -> FakeHttp.response(request, 200, ResponseBody.create(MediaType.get("text/plain"), "")));
		Result<Void> r = this.<Void>one(d -> account.revoke("r", d));
		assertEquals(Outcome.OK, r.outcome);
		assertNull(r.value);
	}
}
