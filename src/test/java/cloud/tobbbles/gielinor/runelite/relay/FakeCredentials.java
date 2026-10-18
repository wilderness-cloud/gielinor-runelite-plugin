package cloud.tobbbles.gielinor.runelite.relay;

import cloud.tobbbles.gielinor.runelite.account.Credentials;
import cloud.tobbbles.gielinor.runelite.account.Link;
import java.util.function.Consumer;

/** Hands out "access-1", or nothing once unlinked, and counts what the pusher asks of it. */
public final class FakeCredentials implements Credentials
{
	public boolean unlinked;
	public int invalidated;
	public int expired;

	@Override
	public void accessToken(Consumer<Link.AccessToken> callback)
	{
		callback.accept(unlinked ? Link.AccessToken.unlinked() : Link.AccessToken.of("access-1"));
	}

	@Override
	public void invalidateAccessToken()
	{
		invalidated++;
	}

	@Override
	public void expire(String why)
	{
		expired++;
	}
}
