package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.Account;
import cloud.tobbbles.gielinor.runelite.v1.Bank;
import cloud.tobbbles.gielinor.runelite.v1.BossRecord;
import cloud.tobbbles.gielinor.runelite.v1.CharacterState;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLog;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogItem;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogPage;
import cloud.tobbbles.gielinor.runelite.v1.CombatAchievementTask;
import cloud.tobbbles.gielinor.runelite.v1.CombatAchievements;
import cloud.tobbbles.gielinor.runelite.v1.DiaryRegion;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.Observed;
import cloud.tobbbles.gielinor.runelite.v1.Quest;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import cloud.tobbbles.gielinor.runelite.v1.Slayer;
import com.google.protobuf.FieldMask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The character's state as the plugin last read it, one value per mask path, and which paths have
 * changed since the relay last acknowledged them.
 *
 * <p>A path is what the relay's {@code update_mask} names: a whole field ({@code bank}), one map
 * entry ({@code skills.ATTACK}), or a field or map entry inside a field
 * ({@code collection_log.unique_total}, {@code combat_achievements.tasks.42}). Paths never go below
 * a map entry, which the relay refuses. A path whose value is null was cleared: it is pushed in the
 * mask with nothing in the state, and the relay forgets it.
 *
 * <p>Pushing entries rather than whole fields matters twice over: a completed combat achievement
 * sends one task, not 655, and collection log items seen in an earlier session survive a session
 * that hasn't opened their page.
 *
 * <p>Every change bumps the path's version. A push carries a {@link Snapshot} of the versions it
 * sent, and an acknowledgement clears only paths that haven't changed again since.
 */
public class StateStore
{
	public static final String ACCOUNT = "account";
	public static final String SKILLS = "skills";
	public static final String QUESTS = "quests";
	public static final String DIARIES = "diaries";
	public static final String COMBAT_ACHIEVEMENTS = "combat_achievements";
	public static final String COLLECTION_LOG = "collection_log";
	public static final String INVENTORY = "inventory";
	public static final String EQUIPMENT = "equipment";
	public static final String BANK = "bank";
	public static final String BOSS_KC = "boss_kc";
	public static final String SLAYER = "slayer";

	/** Whole fields: the path is the field. */
	private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(ACCOUNT, INVENTORY, EQUIPMENT, BANK, SLAYER));
	/** Maps keyed by an upper-snake name: the path is {@code <map>.<KEY>}. */
	private static final Set<String> NAMED_MAPS = new HashSet<>(Arrays.asList(SKILLS, QUESTS, DIARIES, BOSS_KC));

	private final Map<String, Object> values = new HashMap<>();
	private final Map<String, Long> dirty = new HashMap<>();
	private long version;

	/** Records a value; a path whose value hasn't changed stays clean. Null clears it. */
	public synchronized boolean put(String path, Object value)
	{
		if (!knownPath(path))
		{
			throw new IllegalArgumentException("not a path this plugin pushes: " + path);
		}
		// Equal values, including clearing what was never set, are no change.
		if (Objects.equals(values.get(path), value))
		{
			return false;
		}
		values.put(path, value);
		dirty.put(path, ++version);
		return true;
	}

	/** The value last recorded for a path, or null. */
	public synchronized Object get(String path)
	{
		return values.get(path);
	}

	/** Clears every path of a group ({@code bank}, or each {@code skills.*} entry). */
	public synchronized boolean clearGroup(String group)
	{
		boolean changed = false;
		for (String path : new ArrayList<>(values.keySet()))
		{
			if (under(path, group) && values.get(path) != null)
			{
				values.put(path, null);
				dirty.put(path, ++version);
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * The relay has no copy of these paths (PushResponse.missing): send them again. A path naming a
	 * whole field ({@code skills}, {@code collection_log}) means everything we hold under it.
	 */
	public synchronized boolean markMissing(List<String> paths)
	{
		boolean changed = false;
		for (String missing : paths)
		{
			for (String path : values.keySet())
			{
				if (under(path, missing))
				{
					dirty.put(path, ++version);
					changed = true;
				}
			}
		}
		return changed;
	}

	public synchronized boolean hasChanges()
	{
		return !dirty.isEmpty();
	}

	/** Forgets everything: another character logged in. */
	public synchronized void reset()
	{
		values.clear();
		dirty.clear();
	}

	/** What to push now: every changed path, with its current value. */
	public synchronized Snapshot snapshot()
	{
		Building state = new Building();
		FieldMask.Builder mask = FieldMask.newBuilder();
		for (String path : new TreeSet<>(dirty.keySet()))
		{
			mask.addPaths(path);
			Object value = values.get(path);
			if (value != null)
			{
				set(state, path.split("\\."), value);
			}
		}
		return new Snapshot(state.build(), mask.build(), new HashMap<>(dirty));
	}

	/** The relay applied a push: its paths are clean unless they changed again since. */
	public synchronized void acknowledged(Snapshot sent)
	{
		for (Map.Entry<String, Long> e : sent.versions.entrySet())
		{
			if (e.getValue().equals(dirty.get(e.getKey())))
			{
				dirty.remove(e.getKey());
			}
		}
	}

	/** Whether a path is one this plugin pushes, in a shape the relay accepts. */
	public static boolean knownPath(String path)
	{
		String[] p = path.split("\\.", -1);
		switch (p.length)
		{
			case 1:
				return FIELDS.contains(p[0]);
			case 2:
				if (NAMED_MAPS.contains(p[0]))
				{
					return isKey(p[1]);
				}
				if (COMBAT_ACHIEVEMENTS.equals(p[0]))
				{
					return "observed".equals(p[1]);
				}
				return COLLECTION_LOG.equals(p[0])
					&& ("observed".equals(p[1]) || "unique_obtained".equals(p[1]) || "unique_total".equals(p[1]));
			case 3:
				if (COMBAT_ACHIEVEMENTS.equals(p[0]) && "tasks".equals(p[1]))
				{
					return isId(p[2]);
				}
				if (COLLECTION_LOG.equals(p[0]) && "items".equals(p[1]))
				{
					return isId(p[2]);
				}
				return COLLECTION_LOG.equals(p[0]) && "pages".equals(p[1]) && isKey(p[2]);
			default:
				return false;
		}
	}

	private static void set(Building building, String[] p, Object value)
	{
		CharacterState.Builder state = building.state;
		switch (p[0])
		{
			case ACCOUNT:
				state.setAccount((Account) value);
				break;
			case INVENTORY:
				state.setInventory((ItemContainer) value);
				break;
			case EQUIPMENT:
				state.setEquipment((ItemContainer) value);
				break;
			case BANK:
				state.setBank((Bank) value);
				break;
			case SLAYER:
				state.setSlayer((Slayer) value);
				break;
			case SKILLS:
				state.putSkills(p[1], (Skill) value);
				break;
			case QUESTS:
				state.putQuests(p[1], (Quest) value);
				break;
			case DIARIES:
				state.putDiaries(p[1], (DiaryRegion) value);
				break;
			case BOSS_KC:
				state.putBossKc(p[1], (BossRecord) value);
				break;
			case COMBAT_ACHIEVEMENTS:
				if (p.length == 2)
				{
					building.combatAchievements().setObserved((Observed) value);
				}
				else
				{
					building.combatAchievements().putTasks(Integer.parseInt(p[2]), (CombatAchievementTask) value);
				}
				break;
			default:
				setCollectionLog(building.collectionLog(), p, value);
				break;
		}
	}

	private static void setCollectionLog(CollectionLog.Builder log, String[] p, Object value)
	{
		switch (p[1])
		{
			case "observed":
				log.setObserved((Observed) value);
				break;
			case "unique_obtained":
				log.setUniqueObtained((Integer) value);
				break;
			case "unique_total":
				log.setUniqueTotal((Integer) value);
				break;
			case "items":
				log.putItems(Integer.parseInt(p[2]), (CollectionLogItem) value);
				break;
			default:
				log.putPages(p[2], (CollectionLogPage) value);
				break;
		}
	}

	/**
	 * A CharacterState being built. Lite builders have no nested builders, so the two fields that
	 * take paths inside them are built apart and set once at the end.
	 */
	private static final class Building
	{
		final CharacterState.Builder state = CharacterState.newBuilder();
		private CombatAchievements.Builder combatAchievements;
		private CollectionLog.Builder collectionLog;

		CombatAchievements.Builder combatAchievements()
		{
			if (combatAchievements == null)
			{
				combatAchievements = CombatAchievements.newBuilder();
			}
			return combatAchievements;
		}

		CollectionLog.Builder collectionLog()
		{
			if (collectionLog == null)
			{
				collectionLog = CollectionLog.newBuilder();
			}
			return collectionLog;
		}

		CharacterState build()
		{
			if (combatAchievements != null)
			{
				state.setCombatAchievements(combatAchievements);
			}
			if (collectionLog != null)
			{
				state.setCollectionLog(collectionLog);
			}
			return state.build();
		}
	}

	private static boolean under(String path, String prefix)
	{
		return path.equals(prefix) || path.startsWith(prefix + ".");
	}

	/** Upper-snake map keys: A-Z, 0-9 and _. Identifier-safe, so a path never needs quoting. */
	private static boolean isKey(String key)
	{
		return !key.isEmpty() && key.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_');
	}

	/** Game ids: non-negative integers that fit an int32 key. */
	private static boolean isId(String key)
	{
		if (key.isEmpty() || key.length() > 10 || !key.chars().allMatch(c -> c >= '0' && c <= '9'))
		{
			return false;
		}
		return Long.parseLong(key) <= Integer.MAX_VALUE;
	}

	/** One push's worth: the state and mask to send, and the versions they were read at. */
	public static final class Snapshot
	{
		public final CharacterState state;
		public final FieldMask mask;
		final Map<String, Long> versions;

		Snapshot(CharacterState state, FieldMask mask, Map<String, Long> versions)
		{
			this.state = state;
			this.mask = mask;
			this.versions = Collections.unmodifiableMap(versions);
		}

		/** An empty mask: a heartbeat. */
		public boolean isEmpty()
		{
			return versions.isEmpty();
		}
	}
}
