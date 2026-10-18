package cloud.tobbbles.gielinor.runelite.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * The {@link Game} answered by RuneLite's Client. Only straight reads live here, so the logic built
 * on them is tested against a fake. Call it on the client thread.
 */
public class ClientGame implements Game
{
	/** The page title is the header's first child, as RuneLite's Chat Commands plugin reads it. */
	private static final int COLLECTION_LOG_TITLE_INDEX = 0;

	private final Client client;
	private final ItemManager items;

	public ClientGame(Client client, ItemManager items)
	{
		this.client = client;
		this.items = items;
	}

	@Override
	public long accountHash()
	{
		return client.getAccountHash();
	}

	@Override
	public String playerName()
	{
		Player me = client.getLocalPlayer();
		return me != null ? me.getName() : null;
	}

	@Override
	public int world()
	{
		return client.getWorld();
	}

	@Override
	public EnumSet<WorldType> worldTypes()
	{
		return client.getWorldType();
	}

	@Override
	public RuneScapeProfileType profile()
	{
		return RuneScapeProfileType.getCurrent(client);
	}

	@Override
	public int varbit(int id)
	{
		return client.getVarbitValue(id);
	}

	@Override
	public int varp(int id)
	{
		return client.getVarpValue(id);
	}

	@Override
	public int realLevel(Skill skill)
	{
		return client.getRealSkillLevel(skill);
	}

	@Override
	public int experience(Skill skill)
	{
		return client.getSkillExperience(skill);
	}

	@Override
	public int totalLevel()
	{
		return client.getTotalLevel();
	}

	@Override
	public long overallExperience()
	{
		return client.getOverallExperience();
	}

	@Override
	public int combatLevel()
	{
		Player me = client.getLocalPlayer();
		return me != null ? me.getCombatLevel() : 0;
	}

	@Override
	public QuestState questState(Quest quest)
	{
		return quest.getState(client);
	}

	@Override
	public List<Slot> container(int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return null;
		}
		Item[] slots = container.getItems();
		List<Slot> out = new ArrayList<>();
		for (int i = 0; i < slots.length; i++)
		{
			if (slots[i] != null && slots[i].getId() > 0)
			{
				out.add(new Slot(i, slots[i].getId(), slots[i].getQuantity()));
			}
		}
		return out;
	}

	@Override
	public ItemInfo item(int itemId)
	{
		ItemComposition composition = items.getItemComposition(itemId);
		boolean placeholder = composition.getPlaceholderTemplateId() != -1;
		return new ItemInfo(composition.getName(), placeholder ? composition.getPlaceholderId() : -1);
	}

	@Override
	public int[] enumValues(int enumId)
	{
		EnumComposition e = client.getEnum(enumId);
		return e != null ? e.getIntVals() : null;
	}

	@Override
	public String structString(int structId, int param)
	{
		StructComposition s = client.getStructComposition(structId);
		return s != null ? s.getStringValue(param) : null;
	}

	@Override
	public int structInt(int structId, int param)
	{
		StructComposition s = client.getStructComposition(structId);
		return s != null ? s.getIntValue(param) : -1;
	}

	@Override
	public List<Integer> dbRows(int table)
	{
		List<Integer> rows = client.getDBTableRows(table);
		return rows != null ? rows : Collections.emptyList();
	}

	@Override
	public List<Integer> dbRowsByValue(int table, int column, Object value)
	{
		List<Integer> rows = client.getDBRowsByValue(table, column, 0, value);
		return rows != null ? rows : Collections.emptyList();
	}

	@Override
	public Object dbField(int row, int column)
	{
		Object[] values = client.getDBTableField(row, column, 0);
		return values != null && values.length > 0 ? values[0] : null;
	}

	@Override
	public CollectionLogPage viewedCollectionLogPage()
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (header == null || contents == null || header.getChildren() == null || contents.getChildren() == null)
		{
			return null;
		}
		Widget title = header.getChild(COLLECTION_LOG_TITLE_INDEX);
		String name = title != null ? Text.removeTags(title.getText()).trim() : "";
		if (name.isEmpty())
		{
			return null;
		}
		List<CollectionLogEntry> entries = new ArrayList<>();
		for (Widget child : contents.getChildren())
		{
			if (child != null && child.getItemId() > 0)
			{
				// The game draws an item it hasn't seen faded: opacity 0 means obtained.
				boolean obtained = child.getOpacity() == 0;
				entries.add(new CollectionLogEntry(child.getItemId(), obtained, obtained ? child.getItemQuantity() : 0));
			}
		}
		return new CollectionLogPage(name, entries);
	}
}
