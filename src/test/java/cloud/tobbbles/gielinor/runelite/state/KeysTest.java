package cloud.tobbbles.gielinor.runelite.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class KeysTest
{
	@Test
	public void namesBecomeUpperSnakeKeys()
	{
		assertEquals("PHOSANIS_NIGHTMARE", Keys.upperSnake("Phosani's Nightmare"));
		assertEquals("PHOSANIS_NIGHTMARE", Keys.upperSnake("Phosani’s Nightmare"));
		assertEquals("CHAMBERS_OF_XERIC_CHALLENGE_MODE", Keys.upperSnake("Chambers of Xeric: Challenge Mode"));
		assertEquals("DAGANNOTH_KINGS_AND_FRIENDS", Keys.upperSnake("Dagannoth Kings & friends"));
		assertEquals("TZKAL_ZUK", Keys.upperSnake("  TzKal-Zuk!  "));
		assertEquals("K2", Keys.upperSnake("k2"));
	}

	@Test
	public void nothingToKeyIsNull()
	{
		assertNull(Keys.upperSnake(null));
		assertNull(Keys.upperSnake(""));
		assertNull(Keys.upperSnake(" - '"));
	}
}
