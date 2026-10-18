package cloud.tobbbles.gielinor.runelite;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * An OkHttpClient that never touches the network: each request is answered by the next queued
 * handler, and async calls run inline, so a test sees the callback before enqueue() returns.
 */
public final class FakeHttp
{
	public static final MediaType JSON = MediaType.get("application/json");

	private final Deque<Function<Request, Response>> answers = new ArrayDeque<>();
	private final List<Request> requests = new ArrayList<>();
	private final List<byte[]> bodies = new ArrayList<>();
	public final OkHttpClient client;

	public FakeHttp()
	{
		client = new OkHttpClient.Builder()
			.dispatcher(new Dispatcher(new InlineExecutor()))
			.addInterceptor(chain ->
			{
				Request request = chain.request();
				requests.add(request);
				bodies.add(bodyBytes(request));
				Function<Request, Response> answer = answers.poll();
				if (answer == null)
				{
					throw new IOException("no answer queued for " + request.url());
				}
				Response response = answer.apply(request);
				if (response == null)
				{
					throw new IOException("network down");
				}
				return response;
			})
			.build();
	}

	/** Queues a JSON answer. */
	public FakeHttp answer(int code, String json)
	{
		return answer(request -> response(request, code, ResponseBody.create(JSON, json)));
	}

	public FakeHttp answer(Function<Request, Response> answer)
	{
		answers.add(answer);
		return this;
	}

	/** Queues a network failure. */
	public FakeHttp fail()
	{
		return answer(request -> null);
	}

	public List<Request> requests()
	{
		return Collections.unmodifiableList(requests);
	}

	public Request last()
	{
		return requests.get(requests.size() - 1);
	}

	public byte[] lastBody()
	{
		return bodies.get(bodies.size() - 1);
	}

	/** The last request's form body, decoded (a=1&b=2). */
	public String lastForm()
	{
		return new String(lastBody(), java.nio.charset.StandardCharsets.UTF_8);
	}

	public int pending()
	{
		return answers.size();
	}

	public static Response response(Request request, int code, ResponseBody body)
	{
		return new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("")
			.body(body)
			.build();
	}

	private static byte[] bodyBytes(Request request)
	{
		if (request.body() == null)
		{
			return new byte[0];
		}
		try
		{
			Buffer buffer = new Buffer();
			request.body().writeTo(buffer);
			return buffer.readByteArray();
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Runs every task on the calling thread. */
	private static final class InlineExecutor extends AbstractExecutorService
	{
		private boolean shutdown;

		@Override
		public void execute(Runnable command)
		{
			command.run();
		}

		@Override
		public void shutdown()
		{
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			shutdown = true;
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown()
		{
			return shutdown;
		}

		@Override
		public boolean isTerminated()
		{
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}
	}
}
