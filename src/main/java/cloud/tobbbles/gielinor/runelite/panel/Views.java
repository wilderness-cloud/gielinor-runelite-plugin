package cloud.tobbbles.gielinor.runelite.panel;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;

/** Builds what the side panel shows from the link and the pusher. Pure, so it is tested directly. */
public final class Views
{
	private Views()
	{
	}

	/**
	 * @param linkProblem why linking or the link failed, if it did
	 * @param sending     linked, and "Send to Gielinor" is on
	 * @param character   the character being pushed, or null
	 * @param lastAckAt   when the relay last acknowledged a push, or 0
	 * @param pushProblem why pushing is failing, if it is
	 */
	public static GielinorPanel.View of(Link.State state, AccountClient.DeviceCode code, String linkProblem,
		boolean sending, CharacterRef character, long lastAckAt, String pushProblem)
	{
		GielinorPanel.View view = new GielinorPanel.View();
		switch (state)
		{
			case LINKING:
				view.mode = GielinorPanel.View.Mode.LINKING;
				view.userCode = code != null ? code.user_code : null;
				break;
			case LINKED:
				view.mode = GielinorPanel.View.Mode.LINKED;
				break;
			case EXPIRED:
				view.mode = GielinorPanel.View.Mode.EXPIRED;
				break;
			default:
				view.mode = GielinorPanel.View.Mode.UNLINKED;
				break;
		}
		view.sending = sending;
		view.character = character != null && character.getLoggedIn() ? character.getRsn() : null;
		view.lastAckAt = lastAckAt;
		// A link problem is the more fundamental; a push problem only matters while sending.
		view.problem = linkProblem != null ? linkProblem : (sending ? pushProblem : null);
		return view;
	}
}
