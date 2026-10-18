package cloud.tobbbles.gielinor.runelite.account;

/**
 * Where the refresh token is kept between sessions. The plugin keeps it in RuneLite's config
 * (see docs/design.md, "Where the token lives"); tests keep it in memory.
 */
public interface TokenStore
{
	/** The stored refresh token, or null. */
	String refreshToken();

	/** The account service the stored token belongs to, or null. */
	String issuer();

	void save(String issuer, String refreshToken);

	void clear();
}
