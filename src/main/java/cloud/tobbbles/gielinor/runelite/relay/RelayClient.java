package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import cloud.tobbbles.gielinor.runelite.v1.PushResponse;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

/**
 * Sends a push to the RuneLite relay: one Connect unary call,
 * {@code POST <relay>/gielinor.runelite.v1.IngestService/Push}, with a binary protobuf body.
 *
 * <p>Binary, not JSON: JSON writes FieldMask paths in lowerCamelCase, and map-entry paths such as
 * {@code skills.ATTACK} don't survive that. Bodies over {@value #GZIP_OVER} bytes are gzipped
 * (the relay accepts gzip). Errors arrive as Connect's JSON error body, with metadata such as
 * {@code gielinor-last-seq} and {@code Retry-After} in the headers.
 *
 * <p>This is the plugin's only call to the relay, and it never logs a request's contents.
 */
@Slf4j
public class RelayClient
{
	static final String PUSH_PATH = "gielinor.runelite.v1.IngestService/Push";
	static final int GZIP_OVER = 1024;
	private static final MediaType PROTO = MediaType.get("application/proto");

	private final OkHttpClient http;
	private final Gson gson;
	private final String baseUrl;

	/** @param baseUrl where the relay is mounted, e.g. https://gielinor.tobbbles.cloud/runelite */
	public RelayClient(OkHttpClient http, Gson gson, String baseUrl)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
	}

	public void push(PushRequest request, String accessToken, Consumer<PushResult> done)
	{
		HttpUrl url = HttpUrl.parse(baseUrl + PUSH_PATH);
		if (url == null)
		{
			done.accept(PushResult.of(Status.INVALID_ARGUMENT, "the relay address isn't a URL"));
			return;
		}

		byte[] bytes = request.toByteArray();
		Request.Builder http = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + accessToken)
			.header("Connect-Protocol-Version", "1");
		if (bytes.length > GZIP_OVER)
		{
			http.header("Content-Encoding", "gzip");
			bytes = gzip(bytes);
		}

		this.http.newCall(http.post(RequestBody.create(PROTO, bytes)).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(PushResult.of(Status.NETWORK, e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					done.accept(read(response, body != null ? body.bytes() : new byte[0]));
				}
				catch (IOException e)
				{
					done.accept(PushResult.of(Status.NETWORK, e.getMessage()));
				}
			}
		});
	}

	private PushResult read(Response response, byte[] body)
	{
		if (response.code() == 200)
		{
			try
			{
				return PushResult.ok(PushResponse.parseFrom(body));
			}
			catch (InvalidProtocolBufferException e)
			{
				return PushResult.of(Status.OTHER, "unreadable answer from the relay");
			}
		}

		ConnectError error = null;
		try
		{
			error = gson.fromJson(new String(body, java.nio.charset.StandardCharsets.UTF_8), ConnectError.class);
		}
		catch (JsonParseException e)
		{
			log.debug("Gielinor: the relay's error body wasn't JSON");
		}
		Status status = error != null && error.code != null ? Status.fromConnect(error.code) : Status.fromHttp(response.code());
		PushResult result = PushResult.of(status, error != null ? error.message : "HTTP " + response.code());
		result.retryAfterMs = seconds(response.header("Retry-After")) * 1000L;
		String lastSeq = response.header("gielinor-last-seq");
		if (lastSeq != null)
		{
			try
			{
				result.lastSeq = Long.parseLong(lastSeq.trim());
			}
			catch (NumberFormatException e)
			{
				log.debug("Gielinor: unreadable gielinor-last-seq");
			}
		}
		return result;
	}

	private static long seconds(String header)
	{
		if (header == null)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Long.parseLong(header.trim()));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static byte[] gzip(byte[] bytes)
	{
		Buffer out = new Buffer();
		try (BufferedSink sink = Okio.buffer(new GzipSink(out)))
		{
			sink.write(bytes);
		}
		catch (IOException e)
		{
			// Writing to memory doesn't fail.
			throw new IllegalStateException(e);
		}
		return out.readByteArray();
	}

	/** gRPC status codes, as Connect names them, that the pusher tells apart. */
	public enum Status
	{
		OK,
		/** The access token is missing, expired or revoked. */
		UNAUTHENTICATED,
		/** The token can't push (wrong scope). */
		PERMISSION_DENIED,
		/** {@code seq} is not above the last one applied; {@code lastSeq} says what it was. */
		FAILED_PRECONDITION,
		/** Rate-limited or too large. */
		RESOURCE_EXHAUSTED,
		/** The relay can't check tokens right now. */
		UNAVAILABLE,
		/** A request the relay can't accept: a bug on our side. */
		INVALID_ARGUMENT,
		/** No answer at all. */
		NETWORK,
		OTHER;

		static Status fromConnect(String code)
		{
			switch (code)
			{
				case "unauthenticated":
					return UNAUTHENTICATED;
				case "permission_denied":
					return PERMISSION_DENIED;
				case "failed_precondition":
					return FAILED_PRECONDITION;
				case "resource_exhausted":
					return RESOURCE_EXHAUSTED;
				case "unavailable":
					return UNAVAILABLE;
				case "invalid_argument":
					return INVALID_ARGUMENT;
				default:
					return OTHER;
			}
		}

		static Status fromHttp(int code)
		{
			switch (code)
			{
				case 401:
					return UNAUTHENTICATED;
				case 403:
					return PERMISSION_DENIED;
				case 429:
					return RESOURCE_EXHAUSTED;
				case 502:
				case 503:
				case 504:
					return UNAVAILABLE;
				default:
					return OTHER;
			}
		}
	}

	public static final class PushResult
	{
		public final Status status;
		/** Set when the status is OK. */
		public final PushResponse response;
		public final String message;
		public long retryAfterMs;
		/** From {@code gielinor-last-seq}, on FAILED_PRECONDITION. */
		public Long lastSeq;

		private PushResult(Status status, PushResponse response, String message)
		{
			this.status = status;
			this.response = response;
			this.message = message;
		}

		static PushResult ok(PushResponse response)
		{
			return new PushResult(Status.OK, response, null);
		}

		static PushResult of(Status status, String message)
		{
			return new PushResult(status, null, message);
		}
	}

	/** Connect's error body. */
	static final class ConnectError
	{
		String code;
		String message;
	}
}
