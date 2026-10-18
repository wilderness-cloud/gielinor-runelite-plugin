package cloud.tobbbles.gielinor.runelite.panel;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ViewsTest
{
	private static final CharacterRef ZEZIMA = CharacterRef.newBuilder().setRsn("Zezima").setLoggedIn(true).build();

	@Test
	public void eachLinkStateHasItsMode()
	{
		assertEquals(GielinorPanel.View.Mode.UNLINKED, Views.of(Link.State.UNLINKED, null, null, false, null, 0, null).mode);
		assertEquals(GielinorPanel.View.Mode.LINKED, Views.of(Link.State.LINKED, null, null, false, null, 0, null).mode);
		assertEquals(GielinorPanel.View.Mode.EXPIRED, Views.of(Link.State.EXPIRED, null, null, false, null, 0, null).mode);
	}

	@Test
	public void linkingShowsTheCodeOnceThereIsOne()
	{
		GielinorPanel.View view = Views.of(Link.State.LINKING, null, null, false, null, 0, null);
		assertEquals(GielinorPanel.View.Mode.LINKING, view.mode);
		assertNull(view.userCode);

		AccountClient.DeviceCode code = new AccountClient.DeviceCode();
		code.user_code = "ABCD-EFGH";
		assertEquals("ABCD-EFGH", Views.of(Link.State.LINKING, code, null, false, null, 0, null).userCode);
	}

	@Test
	public void showsTheCharacterOnlyWhileLoggedIn()
	{
		GielinorPanel.View view = Views.of(Link.State.LINKED, null, null, true, ZEZIMA, 42L, null);
		assertTrue(view.sending);
		assertEquals("Zezima", view.character);
		assertEquals(42L, view.lastAckAt);

		CharacterRef out = ZEZIMA.toBuilder().setLoggedIn(false).build();
		assertNull(Views.of(Link.State.LINKED, null, null, true, out, 0, null).character);
	}

	@Test
	public void aLinkProblemWinsAndAPushProblemOnlyShowsWhileSending()
	{
		assertEquals("link", Views.of(Link.State.EXPIRED, null, "link", true, null, 0, "push").problem);
		assertEquals("push", Views.of(Link.State.LINKED, null, null, true, null, 0, "push").problem);
		GielinorPanel.View off = Views.of(Link.State.LINKED, null, null, false, null, 0, "push");
		assertNull(off.problem);
		assertFalse(off.sending);
	}
}
