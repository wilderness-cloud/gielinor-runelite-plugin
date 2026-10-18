package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.relay.Pusher;
import cloud.tobbbles.gielinor.runelite.state.Collectors;
import cloud.tobbbles.gielinor.runelite.state.Convert;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * What the plugin does with game events, on the client thread: who is logged in and whether they
 * are tracked, which groups need reading, and when to tell the pusher. The plugin only forwards
 * events here, so this logic is tested without a game client.
 *
 * <p>Reads happen on the next tick after the event that dirtied them, so a burst of var changes is
 * one read. Quests cost a client script each, so they are read on login, when quest points move,
 * and every {@value #QUEST_SWEEP_TICKS} ticks (about five minutes) to catch quests started.
 */
public class GameSession
{
	static final int QUEST_SWEEP_TICKS = 500;

	private final Game game;
	private final Collectors collectors;
	private final Pusher pusher;
	private final BooleanSupplier sending;

	private boolean loginPending;
	private boolean tracked;
	private boolean accountDirty;
	private boolean questsDirty;
	private boolean diariesDirty;
	private boolean combatAchievementsDirty;
	private boolean collectionLogDirty;
	private boolean bossKcDirty;
	private boolean slayerDirty;
	private int ticksSinceQuests;
	private Set<String> wanted = Collections.emptySet();

	/** @param sending linked, and "Send to Gielinor" is on */
	public GameSession(Game game, Collectors collectors, Pusher pusher, BooleanSupplier sending)
	{
		this.game = game;
		this.collectors = collectors;
		this.pusher = pusher;
		this.sending = sending;
	}

	/** Whether the logged-in character is one we push. */
	public boolean tracked()
	{
		return tracked;
	}

	/** The game reached LOGGED_IN; identity settles over the next tick. */
	public void loggedIn()
	{
		loginPending = true;
	}

	/** Back at the login screen, or hopping: say so once. */
	public void leftGame()
	{
		loginPending = false;
		tracked = false;
		pusher.loggedOut();
	}

	/** Sending was switched on or off (link or setting). On while logged in starts afresh. */
	public void sendingChanged(boolean on, boolean loggedIn)
	{
		if (!on)
		{
			tracked = false;
		}
		else if (loggedIn)
		{
			loginPending = true;
		}
	}

	public void tick()
	{
		if (!sending.getAsBoolean())
		{
			return;
		}
		if (loginPending)
		{
			login();
			return;
		}
		if (!tracked)
		{
			return;
		}
		noteWanted();

		boolean changed = false;
		if (accountDirty)
		{
			accountDirty = false;
			changed |= collectors.readAccount();
		}
		if (diariesDirty)
		{
			diariesDirty = false;
			changed |= collectors.readDiaries();
		}
		if (combatAchievementsDirty)
		{
			combatAchievementsDirty = false;
			changed |= collectors.readCombatAchievements();
		}
		if (collectionLogDirty)
		{
			collectionLogDirty = false;
			changed |= collectors.readCollectionLog();
		}
		if (bossKcDirty)
		{
			bossKcDirty = false;
			changed |= collectors.readBossKc();
		}
		if (slayerDirty)
		{
			slayerDirty = false;
			changed |= collectors.readSlayer();
		}
		if (questsDirty || ++ticksSinceQuests >= QUEST_SWEEP_TICKS)
		{
			questsDirty = false;
			ticksSinceQuests = 0;
			changed |= collectors.readQuests();
		}
		if (changed)
		{
			pusher.changed();
		}
	}

	public void statChanged(Skill skill)
	{
		if (active() && collectors.readSkill(skill))
		{
			// Totals and combat level move with a skill.
			accountDirty = true;
			pusher.changed();
		}
	}

	public void containerChanged(int containerId)
	{
		if (active() && collectors.readContainer(containerId))
		{
			pusher.changed();
		}
	}

	/** A varp or varbit changed: mark what it can move, to read on the next tick. */
	public void varChanged(int varpId, int varbitId)
	{
		if (!tracked)
		{
			return;
		}
		if (varpId == VarPlayerID.QP)
		{
			// A quest finished: its state and the account's quest points moved.
			questsDirty = true;
			accountDirty = true;
		}
		if (varbitId == VarbitID.IRONMAN)
		{
			accountDirty = true;
		}
		diariesDirty |= Collectors.isDiaryVarbit(varbitId);
		combatAchievementsDirty |= Collectors.isCombatAchievementVarp(varpId);
		collectionLogDirty |= Collectors.isCollectionLogVarp(varpId);
		bossKcDirty |= collectors.isKillCountVarp(varpId);
		slayerDirty |= Collectors.isSlayerVar(varpId, varbitId);
	}

	/** A collection log page was drawn: record what it shows. */
	public void collectionLogDrawn()
	{
		if (active() && collectors.viewCollectionLogPage(game.viewedCollectionLogPage()))
		{
			pusher.changed();
		}
	}

	/** A "what to send" setting changed: clear what's off, read what's back on. */
	public void groupsChanged()
	{
		if (active() && collectors.readAll())
		{
			pusher.changed();
		}
	}

	private boolean active()
	{
		return tracked && sending.getAsBoolean();
	}

	/** First tick after logging in: who is this, and is it tracked? Then read everything. */
	private void login()
	{
		String name = game.playerName();
		if (name == null)
		{
			// The player hasn't loaded yet: try again next tick.
			return;
		}
		loginPending = false;
		RuneScapeProfileType profile = game.profile();
		tracked = Convert.tracked(profile, game.worldTypes());
		if (!tracked)
		{
			pusher.loggedOut();
			return;
		}
		pusher.loggedIn(CharacterRef.newBuilder()
			.setAccountHash(game.accountHash())
			.setRsn(name)
			.setWorld(game.world())
			.setProfile(profile.name())
			.setLoggedIn(true)
			.build());
		collectors.reset();
		wanted = new HashSet<>(pusher.wanted());
		collectors.readAll();
		ticksSinceQuests = 0;
		pusher.changed();
	}

	/** The relay started wanting a hot field: build it on this tick. */
	private void noteWanted()
	{
		Set<String> now = new HashSet<>(pusher.wanted());
		if (now.equals(wanted))
		{
			return;
		}
		for (String field : now)
		{
			if (!wanted.contains(field))
			{
				combatAchievementsDirty |= StateStore.COMBAT_ACHIEVEMENTS.equals(field);
				collectionLogDirty |= StateStore.COLLECTION_LOG.equals(field);
			}
		}
		wanted = now;
	}
}
