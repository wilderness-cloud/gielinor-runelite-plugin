package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.TokenStore;
import java.util.UUID;

/**
 * The refresh token in RuneLite's config (docs/design.md, "Where the token lives"). It is set and
 * read here only, never shown: there is no config item for it.
 */
class ConfigTokenStore implements TokenStore
{
	static final String REFRESH_TOKEN = "refreshToken";
	static final String ISSUER = "tokenIssuer";
	static final String CLIENT_ID = "clientId";

	private final ConfigStore config;

	ConfigTokenStore(ConfigStore config)
	{
		this.config = config;
	}

	@Override
	public String refreshToken()
	{
		return config.get(REFRESH_TOKEN);
	}

	@Override
	public String issuer()
	{
		return config.get(ISSUER);
	}

	@Override
	public void save(String issuer, String refreshToken)
	{
		config.set(ISSUER, issuer);
		config.set(REFRESH_TOKEN, refreshToken);
	}

	@Override
	public void clear()
	{
		config.unset(REFRESH_TOKEN);
		config.unset(ISSUER);
	}

	/**
	 * One id per install, made on first run: the relay's client_id, which it requires to be 8 to 64
	 * letters, digits, - or _. A stored id that isn't is replaced.
	 */
	static String clientId(ConfigStore config)
	{
		String id = config.get(CLIENT_ID);
		if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}"))
		{
			id = UUID.randomUUID().toString();
			config.set(CLIENT_ID, id);
		}
		return id;
	}
}
