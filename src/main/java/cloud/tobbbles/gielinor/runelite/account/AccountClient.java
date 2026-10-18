package cloud.tobbbles.gielinor.runelite.account;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The OAuth calls the plugin makes to the Gielinor account service: discovery (RFC 8414), the
 * device authorization grant (RFC 8628), refresh (RFC 6749 §6) and revocation (RFC 7009).
 *
 * <p>The plugin is a public client, {@value #CLIENT_ID}: it holds no secret. It asks for
 * {@value #SCOPE} and nothing that opens an API beyond pushing game state. All calls are async on
 * RuneLite's OkHttp client, and every callback runs on an OkHttp thread.
 */
@Slf4j
public class AccountClient
{
	public static final String CLIENT_ID = "gielinor-runelite";
	public static final String SCOPE = "runelite:push offline_access";
	private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

	private final OkHttpClient http;
	private final Gson gson;
	private final String issuer;
	private volatile Metadata metadata;

	public AccountClient(OkHttpClient http, Gson gson, String issuer)
	{
		this.http = http;
		this.gson = gson;
		this.issuer = trimTrailingSlash(issuer);
	}

	public String issuer()
	{
		return issuer;
	}

	/** Starts the device grant: a code for the player to approve. */
	public void requestDeviceCode(Consumer<Result<DeviceCode>> done)
	{
		withMetadata(done, m -> post(m.device_authorization_endpoint,
			new FormBody.Builder().add("client_id", CLIENT_ID).add("scope", SCOPE).build(),
			DeviceCode.class, done, AccountClient::failed));
	}

	/** One poll of the token endpoint for a device code. */
	public void pollDeviceToken(String deviceCode, Consumer<Result<Tokens>> done)
	{
		withMetadata(done, m -> post(m.token_endpoint,
			new FormBody.Builder()
				.add("grant_type", DEVICE_GRANT)
				.add("device_code", deviceCode)
				.add("client_id", CLIENT_ID)
				.build(),
			Tokens.class, done, AccountClient::deviceError));
	}

	/** Trades a refresh token for new tokens. Refresh tokens rotate: keep the new one. */
	public void refresh(String refreshToken, Consumer<Result<Tokens>> done)
	{
		withMetadata(done, m -> post(m.token_endpoint,
			new FormBody.Builder()
				.add("grant_type", "refresh_token")
				.add("refresh_token", refreshToken)
				.add("client_id", CLIENT_ID)
				.build(),
			Tokens.class, done, AccountClient::refreshError));
	}

	/** Revokes a refresh token. Best effort: unlinking goes ahead whatever the answer. */
	public void revoke(String refreshToken, Consumer<Result<Void>> done)
	{
		withMetadata(done, m ->
		{
			if (m.revocation_endpoint == null)
			{
				done.accept(Result.failed("the account service has no revocation endpoint"));
				return;
			}
			post(m.revocation_endpoint,
				new FormBody.Builder()
					.add("token", refreshToken)
					.add("token_type_hint", "refresh_token")
					.add("client_id", CLIENT_ID)
					.build(),
				Void.class, done, AccountClient::failed);
		});
	}

	private <T> void withMetadata(Consumer<Result<T>> done, Consumer<Metadata> then)
	{
		Metadata known = metadata;
		if (known != null)
		{
			then.accept(known);
			return;
		}

		HttpUrl url = HttpUrl.parse(issuer + "/.well-known/oauth-authorization-server");
		if (url == null)
		{
			done.accept(Result.failed("the Gielinor server address isn't a URL: " + issuer));
			return;
		}
		http.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(Result.failed("can't reach the account service (" + e.getMessage() + ")"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					Metadata m = response.isSuccessful() && body != null ? parse(body.string(), Metadata.class) : null;
					if (m == null || m.device_authorization_endpoint == null || m.token_endpoint == null)
					{
						done.accept(Result.failed("the account service doesn't offer device sign-in"));
						return;
					}
					metadata = m;
					then.accept(m);
				}
				catch (IOException e)
				{
					done.accept(Result.failed("can't read the account service's answer"));
				}
			}
		});
	}

	private <T> void post(String endpoint, FormBody form, Class<T> type, Consumer<Result<T>> done,
		ErrorMapper<T> onError)
	{
		HttpUrl url = HttpUrl.parse(endpoint);
		if (url == null)
		{
			done.accept(Result.failed("the account service advertised a bad URL"));
			return;
		}
		http.newCall(new Request.Builder().url(url).post(form).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(Result.failed("can't reach the account service (" + e.getMessage() + ")"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					String text = body != null ? body.string() : "";
					if (response.isSuccessful())
					{
						T value = type == Void.class ? null : parse(text, type);
						done.accept(type == Void.class || value != null
							? Result.ok(value)
							: Result.failed("can't read the account service's answer"));
						return;
					}
					ErrorBody error = parse(text, ErrorBody.class);
					done.accept(onError.map(response.code(), error != null ? error.error : null));
				}
				catch (IOException e)
				{
					done.accept(Result.failed("can't read the account service's answer"));
				}
			}
		});
	}

	private <T> T parse(String json, Class<T> type)
	{
		try
		{
			return gson.fromJson(json, type);
		}
		catch (JsonParseException e)
		{
			log.debug("Gielinor: unreadable answer from the account service", e);
			return null;
		}
	}

	private static <T> Result<T> failed(int status, String error)
	{
		return Result.failed("the account service answered " + status + (error != null ? " (" + error + ")" : ""));
	}

	/** RFC 8628 §3.5: the answers a poll can get. */
	private static Result<Tokens> deviceError(int status, String error)
	{
		if (error == null)
		{
			return failed(status, null);
		}
		switch (error)
		{
			case "authorization_pending":
				return Result.of(Outcome.PENDING);
			case "slow_down":
				return Result.of(Outcome.SLOW_DOWN);
			case "access_denied":
				return Result.of(Outcome.DENIED);
			case "expired_token":
				return Result.of(Outcome.EXPIRED);
			default:
				return failed(status, error);
		}
	}

	/** invalid_grant means the link is over (revoked, expired or replayed); anything else may pass. */
	private static Result<Tokens> refreshError(int status, String error)
	{
		if ("invalid_grant".equals(error) || "invalid_client".equals(error) || "unauthorized_client".equals(error))
		{
			return Result.of(Outcome.INVALID_GRANT);
		}
		return failed(status, error);
	}

	private static String trimTrailingSlash(String url)
	{
		String s = url == null ? "" : url.trim();
		while (s.endsWith("/"))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	private interface ErrorMapper<T>
	{
		Result<T> map(int status, String error);
	}

	/** What a call came to. */
	public enum Outcome
	{
		OK,
		/** Device grant: not approved yet. */
		PENDING,
		/** Device grant: poll less often. */
		SLOW_DOWN,
		/** Device grant: the player declined. */
		DENIED,
		/** Device grant: the code ran out before it was approved. */
		EXPIRED,
		/** Refresh: the refresh token is no longer valid; the player must link again. */
		INVALID_GRANT,
		/** Anything else, including the network. Worth trying again later. */
		FAILED
	}

	public static final class Result<T>
	{
		public final Outcome outcome;
		public final T value;
		/** Why it failed, for the panel and the log. */
		public final String detail;

		private Result(Outcome outcome, T value, String detail)
		{
			this.outcome = outcome;
			this.value = value;
			this.detail = detail;
		}

		static <T> Result<T> ok(T value)
		{
			return new Result<>(Outcome.OK, value, null);
		}

		static <T> Result<T> of(Outcome outcome)
		{
			return new Result<>(outcome, null, null);
		}

		static <T> Result<T> failed(String detail)
		{
			return new Result<>(Outcome.FAILED, null, detail);
		}
	}

	// Field names follow the OAuth JSON.
	static final class Metadata
	{
		String device_authorization_endpoint;
		String token_endpoint;
		String revocation_endpoint;
	}

	public static final class DeviceCode
	{
		public String device_code;
		public String user_code;
		public String verification_uri;
		public String verification_uri_complete;
		public int expires_in;
		public int interval;
	}

	public static final class Tokens
	{
		public String access_token;
		public String refresh_token;
		public long expires_in;
		public String scope;
	}

	static final class ErrorBody
	{
		String error;
	}
}
