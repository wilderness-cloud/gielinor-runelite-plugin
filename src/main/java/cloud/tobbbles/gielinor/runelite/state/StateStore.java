package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.Account;
import cloud.tobbbles.gielinor.runelite.v1.Bank;
import cloud.tobbbles.gielinor.runelite.v1.CharacterState;
import cloud.tobbbles.gielinor.runelite.v1.DiaryRegion;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.Quest;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import com.google.protobuf.FieldMask;
import com.google.protobuf.MessageLite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The character's state as the plugin last read it, one value per mask path, and which paths have
 * changed since the relay last acknowledged them.
 *
 * <p>A path is a whole field ({@code bank}, {@code account}) or one map entry
 * ({@code skills.ATTACK}, {@code quests.DRAGON_SLAYER_II}), which is how the relay's
 * {@code update_mask} names what a push replaces. A path whose value is null was cleared: it is
 * pushed in the mask with nothing in the state, and the relay forgets it.
 *
 * <p>Every change bumps the path's version. A push carries a {@link Snapshot} of the versions it
 * sent, and an acknowledgement clears only paths that haven't changed again since, so nothing
 * read while a push was in flight is lost.
 */
public class StateStore
{
	public static final String ACCOUNT = "account";
	public static final String SKILLS = "skills";
	public static final String QUESTS = "quests";
	public static final String DIARIES = "diaries";
	public static final String INVENTORY = "inventory";
	public static final String EQUIPMENT = "equipment";
	public static final String BANK = "bank";

	private final Map<String, MessageLite> values = new HashMap<>();
	private final Map<String, Long> dirty = new HashMap<>();
	private long version;

	/** Records a value; a path whose value hasn't changed stays clean. Null clears it. */
	public synchronized boolean put(String path, MessageLite value)
	{
		if (!knownPath(path))
		{
			throw new IllegalArgumentException("not a path this plugin pushes: " + path);
		}
		MessageLite before = values.get(path);
		// Equal values, including clearing what was never set, are no change.
		if (Objects.equals(before, value))
		{
			return false;
		}
		values.put(path, value);
		dirty.put(path, ++version);
		return true;
	}

	/** Clears every path of a group ({@code bank}, or each {@code skills.*} entry). */
	public synchronized boolean clearGroup(String group)
	{
		boolean changed = false;
		for (String path : new ArrayList<>(values.keySet()))
		{
			if ((path.equals(group) || path.startsWith(group + ".")) && values.get(path) != null)
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
	 * whole map ({@code skills}) means every entry we hold.
	 */
	public synchronized boolean markMissing(List<String> paths)
	{
		boolean changed = false;
		for (String missing : paths)
		{
			for (String path : values.keySet())
			{
				if (path.equals(missing) || path.startsWith(missing + "."))
				{
					dirty.put(path, ++version);
					changed = true;
				}
			}
		}
		return changed;
	}

	/** Every path we hold goes out again: a new character, or a relay that lost its copy. */
	public synchronized void markAllDirty()
	{
		for (String path : values.keySet())
		{
			dirty.put(path, ++version);
		}
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
		CharacterState.Builder state = CharacterState.newBuilder();
		FieldMask.Builder mask = FieldMask.newBuilder();
		for (String path : new TreeSet<>(dirty.keySet()))
		{
			mask.addPaths(path);
			MessageLite value = values.get(path);
			if (value != null)
			{
				set(state, path, value);
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

	static boolean knownPath(String path)
	{
		switch (group(path))
		{
			case ACCOUNT:
			case INVENTORY:
			case EQUIPMENT:
			case BANK:
				return path.indexOf('.') < 0;
			case SKILLS:
			case QUESTS:
			case DIARIES:
				return key(path) != null;
			default:
				return false;
		}
	}

	private static void set(CharacterState.Builder state, String path, MessageLite value)
	{
		switch (group(path))
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
			case SKILLS:
				state.putSkills(key(path), (Skill) value);
				break;
			case QUESTS:
				state.putQuests(key(path), (Quest) value);
				break;
			case DIARIES:
				state.putDiaries(key(path), (DiaryRegion) value);
				break;
			default:
				throw new IllegalStateException(path);
		}
	}

	private static String group(String path)
	{
		int dot = path.indexOf('.');
		return dot < 0 ? path : path.substring(0, dot);
	}

	/** The map key of an entry path, or null. Keys are identifier-safe: A-Z, 0-9 and _. */
	private static String key(String path)
	{
		int dot = path.indexOf('.');
		if (dot < 0 || dot == path.length() - 1)
		{
			return null;
		}
		String key = path.substring(dot + 1);
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_')
			{
				return null;
			}
		}
		return key;
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
