package cloud.tobbbles.gielinor.runelite.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/** A Game a test fills in: a character, its vars and items, and the cache tables it reads. */
public final class FakeGame implements Game
{
	public long accountHash = 123456789L;
	public String playerName = "Zezima";
	public int world = 302;
	public EnumSet<WorldType> worldTypes = EnumSet.of(WorldType.MEMBERS);
	public RuneScapeProfileType profile = RuneScapeProfileType.STANDARD;
	public int totalLevel = 1500;
	public long overallExperience = 50_000_000L;
	public int combatLevel = 100;

	public final Map<Integer, Integer> varbits = new HashMap<>();
	public final Map<Integer, Integer> varps = new HashMap<>();
	public final Map<Skill, int[]> skills = new EnumMap<>(Skill.class);
	public final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
	public final Map<Integer, List<Slot>> containers = new HashMap<>();
	public final Map<Integer, ItemInfo> items = new HashMap<>();
	public final Map<Integer, int[]> enums = new HashMap<>();
	public final Map<Integer, Map<Integer, Object>> structs = new HashMap<>();
	public final Map<Integer, List<Integer>> dbTables = new HashMap<>();
	public final Map<Integer, Map<Integer, Object>> dbRows = new HashMap<>();
	public CollectionLogPage viewedPage;
	/** How many quest-state scripts were run, to check quests aren't read every tick. */
	public int questReads;

	public FakeGame varbit(int id, int value)
	{
		varbits.put(id, value);
		return this;
	}

	public FakeGame varp(int id, int value)
	{
		varps.put(id, value);
		return this;
	}

	public FakeGame skill(Skill skill, int level, int xp)
	{
		skills.put(skill, new int[]{level, xp});
		return this;
	}

	public FakeGame item(int id, String name)
	{
		items.put(id, new ItemInfo(name, -1));
		return this;
	}

	public FakeGame placeholder(int id, int realId)
	{
		items.put(id, new ItemInfo(null, realId));
		return this;
	}

	public FakeGame container(int id, Slot... slots)
	{
		List<Slot> list = new ArrayList<>();
		Collections.addAll(list, slots);
		containers.put(id, list);
		return this;
	}

	public FakeGame enumOf(int id, int... values)
	{
		enums.put(id, values);
		return this;
	}

	public FakeGame struct(int id, int param, Object value)
	{
		structs.computeIfAbsent(id, k -> new HashMap<>()).put(param, value);
		return this;
	}

	/** Adds a DB row to a table with its columns' first values. */
	public FakeGame dbRow(int table, int row, Object... columnsAndValues)
	{
		dbTables.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
		Map<Integer, Object> fields = dbRows.computeIfAbsent(row, k -> new HashMap<>());
		for (int i = 0; i < columnsAndValues.length; i += 2)
		{
			fields.put((Integer) columnsAndValues[i], columnsAndValues[i + 1]);
		}
		return this;
	}

	@Override
	public long accountHash()
	{
		return accountHash;
	}

	@Override
	public String playerName()
	{
		return playerName;
	}

	@Override
	public int world()
	{
		return world;
	}

	@Override
	public EnumSet<WorldType> worldTypes()
	{
		return worldTypes;
	}

	@Override
	public RuneScapeProfileType profile()
	{
		return profile;
	}

	@Override
	public int varbit(int id)
	{
		return varbits.getOrDefault(id, 0);
	}

	@Override
	public int varp(int id)
	{
		return varps.getOrDefault(id, 0);
	}

	@Override
	public int realLevel(Skill skill)
	{
		return skills.getOrDefault(skill, new int[]{1, 0})[0];
	}

	@Override
	public int experience(Skill skill)
	{
		return skills.getOrDefault(skill, new int[]{1, 0})[1];
	}

	@Override
	public int totalLevel()
	{
		return totalLevel;
	}

	@Override
	public long overallExperience()
	{
		return overallExperience;
	}

	@Override
	public int combatLevel()
	{
		return combatLevel;
	}

	@Override
	public QuestState questState(Quest quest)
	{
		questReads++;
		return quests.getOrDefault(quest, QuestState.NOT_STARTED);
	}

	@Override
	public List<Slot> container(int containerId)
	{
		return containers.get(containerId);
	}

	@Override
	public ItemInfo item(int itemId)
	{
		return items.getOrDefault(itemId, new ItemInfo("Item " + itemId, -1));
	}

	@Override
	public int[] enumValues(int enumId)
	{
		return enums.get(enumId);
	}

	@Override
	public String structString(int structId, int param)
	{
		Object value = structs.getOrDefault(structId, Collections.emptyMap()).get(param);
		return value instanceof String ? (String) value : null;
	}

	@Override
	public int structInt(int structId, int param)
	{
		Object value = structs.getOrDefault(structId, Collections.emptyMap()).get(param);
		return value instanceof Integer ? (Integer) value : -1;
	}

	@Override
	public List<Integer> dbRows(int table)
	{
		return dbTables.getOrDefault(table, Collections.emptyList());
	}

	@Override
	public List<Integer> dbRowsByValue(int table, int column, Object value)
	{
		List<Integer> out = new ArrayList<>();
		for (int row : dbRows(table))
		{
			if (value.equals(dbField(row, column)))
			{
				out.add(row);
			}
		}
		return out;
	}

	@Override
	public Object dbField(int row, int column)
	{
		return dbRows.getOrDefault(row, Collections.emptyMap()).get(column);
	}

	@Override
	public CollectionLogPage viewedCollectionLogPage()
	{
		return viewedPage;
	}
}
