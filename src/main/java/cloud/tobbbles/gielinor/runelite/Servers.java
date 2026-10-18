package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;

/** The Gielinor server's address, and the page a device code is approved on. */
final class Servers
{
	private Servers()
	{
	}

	/** The configured server without trailing slashes; the default when blank. */
	static String normalise(String server)
	{
		String s = server == null ? "" : server.trim();
		while (s.endsWith("/"))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s.isEmpty() ? GielinorConfig.DEFAULT_SERVER : s;
	}

	/** The approval page for a code, with the code filled in when the server offers that. */
	static String approvalUrl(AccountClient.DeviceCode code)
	{
		if (code == null)
		{
			return null;
		}
		return code.verification_uri_complete != null ? code.verification_uri_complete : code.verification_uri;
	}
}
