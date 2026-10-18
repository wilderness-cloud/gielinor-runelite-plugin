package cloud.tobbbles.gielinor.runelite.state;

import java.util.Locale;

/** Map keys in the contract's upper snake case: "Phosani's Nightmare" is PHOSANIS_NIGHTMARE. */
public final class Keys
{
	private Keys()
	{
	}

	/**
	 * Letters and digits upper-cased; "&amp;" becomes AND; apostrophes vanish (so a possessive stays
	 * one word); any other run of characters becomes one underscore. Null or blank gives null.
	 */
	public static String upperSnake(String name)
	{
		if (name == null)
		{
			return null;
		}
		String s = name.replace("&", " and ").replace("'", "").replace("’", "").toUpperCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(s.length());
		boolean gap = false;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
			{
				if (gap && out.length() > 0)
				{
					out.append('_');
				}
				out.append(c);
				gap = false;
			}
			else
			{
				gap = true;
			}
		}
		return out.length() > 0 ? out.toString() : null;
	}
}
