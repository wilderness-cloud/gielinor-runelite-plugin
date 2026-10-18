package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.relay.RelayClient.PushResult;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient.Status;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import cloud.tobbbles.gielinor.runelite.v1.PushResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.assertNull;

/** Holds each push until the test answers it. */
/** Holds each push until the test answers it. */
public final class FakeTransport implements Pusher.Transport
{
	public final List<PushRequest> pushes = new ArrayList<>();
	public final List<String> tokens = new ArrayList<>();
	public Consumer<PushResult> waiting;

	@Override
	public void push(PushRequest request, String accessToken, Consumer<PushResult> done)
	{
		assertNull("one push at a time", waiting);
		pushes.add(request);
		tokens.add(accessToken);
		waiting = done;
	}

	public PushRequest last()
	{
		return pushes.get(pushes.size() - 1);
	}

	public void ok()
	{
		ok(PushResponse.getDefaultInstance());
	}

	public void ok(PushResponse response)
	{
		answer(PushResult.ok(response.toBuilder().setAckedSeq(last().getSeq()).build()));
	}

	public void fail(Status status, long retryAfterMs)
	{
		PushResult result = PushResult.of(status, status.name());
		result.retryAfterMs = retryAfterMs;
		answer(result);
	}

	public void answer(PushResult result)
	{
		Consumer<PushResult> done = waiting;
		waiting = null;
		done.accept(result);
	}
}
