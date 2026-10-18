package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogItem;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogPage;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The collection log, as far as the game lets a plugin see it:
 * <ul>
 * <li>the unique counts, always (two varps);</li>
 * <li>the catalog of tabs, pages and their items, from the game's tables (structs and enums,
 * walked as its proc_collection_draw_list script does);</li>
 * <li>each item's obtained state and count, only for pages the player opens this session: the
 * game only draws a page's items when it is on screen.</li>
 * </ul>
 *
 * <p>The struct, enum and param ids are the game cache's; RuneLite names none of them. Adapted
 * from runelite-mcp-server-plugin (BSD-2-Clause).
 */
final class CollectionLogReader
{
	/** Bosses, Raids, Clues, Minigames, Other. */
	static final int[] TAB_STRUCTS = {471, 472, 473, 474, 475};
	static final int TAB_NAME_PARAM = 682;
	static final int TAB_PAGES_ENUM_PARAM = 683;
	static final int PAGE_NAME_PARAM = 689;
	static final int PAGE_ITEMS_ENUM_PARAM = 690;

	/** Page key to its catalog entry (name, tab, items), built once per session. */
	private Map<String, CollectionLogPage> catalog;
	private final Set<String> viewed = new HashSet<>();

	/** A new session or character: nothing has been viewed yet. */
	void reset()
	{
		catalog = null;
		viewed.clear();
	}

	/** Every page, keyed in upper snake case, in the order the log lists them. */
	Map<String, CollectionLogPage> catalog(Game game)
	{
		if (catalog == null)
		{
			catalog = buildCatalog(game);
		}
		return catalog;
	}

	/** Whether every page in the catalog has been opened this session. */
	boolean complete(Game game)
	{
		Map<String, CollectionLogPage> pages = catalog(game);
		return !pages.isEmpty() && viewed.containsAll(pages.keySet());
	}

	/**
	 * A page the player has on screen: its entry (marked viewed) and its items' states. Null for
	 * a page without a usable name.
	 */
	Viewed view(Game game, Game.CollectionLogPage page)
	{
		String key = Keys.upperSnake(page.name);
		if (key == null)
		{
			return null;
		}
		viewed.add(key);
		CollectionLogPage known = catalog(game).get(key);
		CollectionLogPage.Builder entry = known != null
			? known.toBuilder()
			: CollectionLogPage.newBuilder().setName(page.name);
		if (known == null)
		{
			for (Game.CollectionLogEntry item : page.items)
			{
				entry.addItemIds(item.id);
			}
		}
		entry.setViewedThisSession(true);

		Map<Integer, CollectionLogItem> items = new LinkedHashMap<>();
		for (Game.CollectionLogEntry item : page.items)
		{
			Game.ItemInfo info = game.item(item.id);
			items.put(item.id, CollectionLogItem.newBuilder()
				.setName(info != null && info.name != null ? info.name : "")
				.setObtained(item.obtained)
				.setQty(item.obtained ? item.quantity : 0)
				.build());
		}
		return new Viewed(key, entry.build(), items);
	}

	private static Map<String, CollectionLogPage> buildCatalog(Game game)
	{
		Map<String, CollectionLogPage> pages = new LinkedHashMap<>();
		for (int tabStruct : TAB_STRUCTS)
		{
			String tab = Keys.upperSnake(game.structString(tabStruct, TAB_NAME_PARAM));
			int[] pageStructs = tab != null ? game.enumValues(game.structInt(tabStruct, TAB_PAGES_ENUM_PARAM)) : null;
			if (pageStructs == null)
			{
				continue;
			}
			for (int pageStruct : pageStructs)
			{
				String name = game.structString(pageStruct, PAGE_NAME_PARAM);
				String key = Keys.upperSnake(name);
				if (key == null)
				{
					continue;
				}
				CollectionLogPage.Builder page = CollectionLogPage.newBuilder().setName(name).setTab(tab);
				int[] items = game.enumValues(game.structInt(pageStruct, PAGE_ITEMS_ENUM_PARAM));
				if (items != null)
				{
					for (int item : items)
					{
						page.addItemIds(item);
					}
				}
				pages.putIfAbsent(key, page.build());
			}
		}
		return Collections.unmodifiableMap(pages);
	}

	/** One viewed page: its key, entry and items. */
	static final class Viewed
	{
		final String key;
		final CollectionLogPage page;
		final Map<Integer, CollectionLogItem> items;

		Viewed(String key, CollectionLogPage page, Map<Integer, CollectionLogItem> items)
		{
			this.key = key;
			this.page = page;
			this.items = items;
		}
	}
}
