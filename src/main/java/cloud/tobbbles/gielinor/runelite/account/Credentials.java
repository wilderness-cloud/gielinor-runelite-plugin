package cloud.tobbbles.gielinor.runelite.account;

import java.util.function.Consumer;

/** What the pusher needs from the link: a token to push with, and a way to report a bad one. */
public interface Credentials
{
	/** Calls back with a current access token (or why there isn't one). */
	void accessToken(Consumer<Link.AccessToken> callback);

	/** The relay refused the token: get a fresh one next time. */
	void invalidateAccessToken();

	/** The relay says this link may never push: end it. */
	void expire(String why);
}
