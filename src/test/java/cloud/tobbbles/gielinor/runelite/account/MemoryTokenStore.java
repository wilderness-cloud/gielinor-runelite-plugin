package cloud.tobbbles.gielinor.runelite.account;

/** A TokenStore in memory. */
public final class MemoryTokenStore implements TokenStore
{
	String refreshToken;
	String issuer;

	@Override
	public String refreshToken()
	{
		return refreshToken;
	}

	@Override
	public String issuer()
	{
		return issuer;
	}

	@Override
	public void save(String issuer, String refreshToken)
	{
		this.issuer = issuer;
		this.refreshToken = refreshToken;
	}

	@Override
	public void clear()
	{
		issuer = null;
		refreshToken = null;
	}
}
