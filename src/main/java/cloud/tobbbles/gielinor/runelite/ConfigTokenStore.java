package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.TokenStore;
import net.runelite.client.config.ConfigManager;

/**
 * The refresh token in RuneLite's config (docs/design.md, "Where the token lives"). It is set and
 * read here only, never shown: there is no config item for it.
 */
class ConfigTokenStore implements TokenStore
{
	private static final String REFRESH_TOKEN = "refreshToken";
	private static final String ISSUER = "tokenIssuer";

	private final ConfigManager config;

	ConfigTokenStore(ConfigManager config)
	{
		this.config = config;
	}

	@Override
	public String refreshToken()
	{
		return config.getConfiguration(GielinorConfig.GROUP, REFRESH_TOKEN);
	}

	@Override
	public String issuer()
	{
		return config.getConfiguration(GielinorConfig.GROUP, ISSUER);
	}

	@Override
	public void save(String issuer, String refreshToken)
	{
		config.setConfiguration(GielinorConfig.GROUP, ISSUER, issuer);
		config.setConfiguration(GielinorConfig.GROUP, REFRESH_TOKEN, refreshToken);
	}

	@Override
	public void clear()
	{
		config.unsetConfiguration(GielinorConfig.GROUP, REFRESH_TOKEN);
		config.unsetConfiguration(GielinorConfig.GROUP, ISSUER);
	}
}
