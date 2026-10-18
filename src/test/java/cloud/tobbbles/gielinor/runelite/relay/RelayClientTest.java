package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.FakeHttp;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.PushResult;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.Status;
import cloud.tobbbles.gielinor.runelite.v1.CharacterState;
import cloud.tobbbles.gielinor.runelite.v1.Item;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import cloud.tobbbles.gielinor.runelite.v1.PushResponse;
import com.google.gson.Gson;
import com.google.protobuf.FieldMask;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.GzipSource;
import okio.Okio;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class RelayClientTest
{
	private final FakeHttp http = new FakeHttp();
	private final RelayClient relay = new RelayClient(http.client, new Gson(), "https://gielinor.test/runelite");

	private PushResult push(PushRequest request)
	{
		List<PushResult> results = new ArrayList<>();
		relay.push(request, "access-1", results::add);
		assertEquals(1, results.size());
		return results.get(0);
	}

	private static PushRequest small()
	{
		return PushRequest.newBuilder().setClientId("install-1").setSeq(42).build();
	}

	private static Response error(okhttp3.Request request, int status, String json, String... headers)
	{
		Response.Builder r = FakeHttp.response(request, status, ResponseBody.create(FakeHttp.JSON, json)).newBuilder();
		for (int i = 0; i < headers.length; i += 2)
		{
			r.header(headers[i], headers[i + 1]);
		}
		return r.build();
	}

	@Test
	public void sendsBinaryProtobufOverConnect() throws Exception
	{
		PushResponse answer = PushResponse.newBuilder()
			.setAckedSeq(42)
			.setNextHeartbeatS(90)
			.setMissing(FieldMask.newBuilder().addPaths("bank"))
			.build();
		http.answer(request -> FakeHttp.response(request, 200,
			ResponseBody.create(MediaType.get("application/proto"), answer.toByteArray())));

		PushResult result = push(small());

		assertEquals(Status.OK, result.status);
		assertEquals(answer, result.response);
		assertEquals("https://gielinor.test/runelite/gielinor.runelite.v1.IngestService/Push", http.last().url().toString());
		assertEquals("Bearer access-1", http.last().header("Authorization"));
		assertEquals("1", http.last().header("Connect-Protocol-Version"));
		assertEquals("application/proto", http.last().body().contentType().toString());
		assertNull(http.last().header("Content-Encoding"));
		assertEquals(small(), PushRequest.parseFrom(http.lastBody()));
	}

	@Test
	public void gzipsALargePush() throws Exception
	{
		ItemContainer.Builder bank = ItemContainer.newBuilder();
		for (int i = 0; i < 400; i++)
		{
			bank.addItems(Item.newBuilder().setId(i).setQty(i * 7).setName("Item " + i));
		}
		PushRequest big = PushRequest.newBuilder()
			.setClientId("install-1")
			.setSeq(43)
			.setState(CharacterState.newBuilder().setInventory(bank))
			.build();
		http.answer(request -> FakeHttp.response(request, 200,
			ResponseBody.create(MediaType.get("application/proto"), new byte[0])));

		push(big);

		assertEquals("gzip", http.last().header("Content-Encoding"));
		Buffer body = new Buffer().write(http.lastBody());
		byte[] inflated = Okio.buffer(new GzipSource(body)).readByteArray();
		assertEquals(big, PushRequest.parseFrom(inflated));
	}

	@Test
	public void readsConnectErrorsAndTheirMetadata()
	{
		http.answer(request -> error(request, 400,
			"{\"code\":\"failed_precondition\",\"message\":\"seq 41 is not above 57\"}",
			"gielinor-last-seq", "57"));
		PushResult stale = push(small());
		assertEquals(Status.FAILED_PRECONDITION, stale.status);
		assertEquals(Long.valueOf(57), stale.lastSeq);

		http.answer(request -> error(request, 429,
			"{\"code\":\"resource_exhausted\",\"message\":\"too many pushes\"}", "Retry-After", "10"));
		PushResult slow = push(small());
		assertEquals(Status.RESOURCE_EXHAUSTED, slow.status);
		assertEquals(10_000, slow.retryAfterMs);

		http.answer(request -> error(request, 401, "{\"code\":\"unauthenticated\",\"message\":\"token expired\"}"));
		assertEquals(Status.UNAUTHENTICATED, push(small()).status);

		http.answer(request -> error(request, 403, "{\"code\":\"permission_denied\",\"message\":\"no scope\"}"));
		assertEquals(Status.PERMISSION_DENIED, push(small()).status);
	}

	@Test
	public void fallsBackToTheHttpStatusWithoutAConnectBody()
	{
		http.answer(request -> FakeHttp.response(request, 503,
			ResponseBody.create(MediaType.get("text/html"), "<html>bad gateway</html>")));
		assertEquals(Status.UNAVAILABLE, push(small()).status);
	}

	@Test
	public void aDroppedConnectionIsANetworkFailure()
	{
		http.fail();
		assertEquals(Status.NETWORK, push(small()).status);
	}

	@Test
	public void anAddressThatIsntAUrlIsRefusedLocally()
	{
		RelayClient bad = new RelayClient(http.client, new Gson(), "nope/");
		List<PushResult> results = new ArrayList<>();
		bad.push(small(), "access-1", results::add);
		assertEquals(Status.INVALID_ARGUMENT, results.get(0).status);
		assertEquals(0, http.requests().size());
	}

	@Test
	public void anUnreadableAnswerIsOther()
	{
		http.answer(request -> FakeHttp.response(request, 200,
			ResponseBody.create(MediaType.get("application/proto"), new byte[]{(byte) 0xff, 0x01})));
		assertEquals(Status.OTHER, push(small()).status);
	}

	@Test
	public void unreadableHeadersAreIgnored()
	{
		http.answer(request -> error(request, 400, "{\"code\":\"failed_precondition\"}",
			"gielinor-last-seq", "soon", "Retry-After", "later"));
		PushResult result = push(small());
		assertEquals(Status.FAILED_PRECONDITION, result.status);
		assertNull(result.lastSeq);
		assertEquals(0, result.retryAfterMs);

		http.answer(request -> error(request, 429, "{}", "Retry-After", "-5"));
		assertEquals(0, push(small()).retryAfterMs);
	}

	@Test
	public void connectCodesAndHttpStatuses()
	{
		assertEquals(Status.UNAVAILABLE, Status.fromConnect("unavailable"));
		assertEquals(Status.INVALID_ARGUMENT, Status.fromConnect("invalid_argument"));
		assertEquals(Status.OTHER, Status.fromConnect("internal"));
		assertEquals(Status.UNAUTHENTICATED, Status.fromHttp(401));
		assertEquals(Status.PERMISSION_DENIED, Status.fromHttp(403));
		assertEquals(Status.RESOURCE_EXHAUSTED, Status.fromHttp(429));
		assertEquals(Status.UNAVAILABLE, Status.fromHttp(502));
		assertEquals(Status.UNAVAILABLE, Status.fromHttp(504));
		assertEquals(Status.OTHER, Status.fromHttp(500));
	}
}
