package cloud.tobbbles.gielinor.runelite.game;

import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Everything the plugin reads from the game, in plain types. {@link ClientGame} answers from
 * RuneLite's Client on the client thread; tests answer from a fake, which is how the readers are
 * tested without a game client (and without the mocking libraries the Hub's build doesn't have).
 */
public interface Game
{
	// Identity.

	long accountHash();

	/** The local player's name, or null before the player has loaded. */
	String playerName();

	int world();

	EnumSet<WorldType> worldTypes();

	/** RuneLite's profile for the current world; null if it can't tell. */
	RuneScapeProfileType profile();

	// Vars and stats.

	int varbit(int id);

	int varp(int id);

	int realLevel(Skill skill);

	int experience(Skill skill);

	int totalLevel();

	long overallExperience();

	/** The local player's combat level, or 0 before the player has loaded. */
	int combatLevel();

	/** Runs the quest's state script; expensive, about one script per call. */
	QuestState questState(Quest quest);

	// Items.

	/** An item container's slots, or null when the client doesn't hold that container. */
	List<Slot> container(int containerId);

	ItemInfo item(int itemId);

	// Cache data.

	/** An enum's int values, or null if the enum doesn't exist. */
	int[] enumValues(int enumId);

	/** A struct's string param, or null if the struct or param doesn't exist. */
	String structString(int structId, int param);

	/** A struct's int param, or -1 if the struct doesn't exist. */
	int structInt(int structId, int param);

	List<Integer> dbRows(int table);

	List<Integer> dbRowsByValue(int table, int column, Object value);

	/** The first value of a DB row's column, or null. */
	Object dbField(int row, int column);

	// Interfaces.

	/** The collection log page on screen, or null if none is. */
	CollectionLogPage viewedCollectionLogPage();

	/** One item container slot. Empty slots are left out. */
	final class Slot
	{
		public final int index;
		public final int id;
		public final int quantity;

		public Slot(int index, int id, int quantity)
		{
			this.index = index;
			this.id = id;
			this.quantity = quantity;
		}
	}

	/** What an item id is: its name, and the real item if it is a bank placeholder. */
	final class ItemInfo
	{
		public final String name;
		/** The real item's id when this id is a placeholder, else -1. */
		public final int placeholderFor;

		public ItemInfo(String name, int placeholderFor)
		{
			this.name = name;
			this.placeholderFor = placeholderFor;
		}
	}

	/** A collection log page as drawn: its title and each item's state. */
	final class CollectionLogPage
	{
		public final String name;
		public final List<CollectionLogEntry> items;

		public CollectionLogPage(String name, List<CollectionLogEntry> items)
		{
			this.name = name;
			this.items = items;
		}
	}

	final class CollectionLogEntry
	{
		public final int id;
		public final boolean obtained;
		public final int quantity;

		public CollectionLogEntry(int id, boolean obtained, int quantity)
		{
			this.id = id;
			this.obtained = obtained;
			this.quantity = quantity;
		}
	}
}
