package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.Account;
import cloud.tobbbles.gielinor.runelite.v1.Bank;
import cloud.tobbbles.gielinor.runelite.v1.BossRecord;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogItem;
import cloud.tobbbles.gielinor.runelite.v1.CollectionLogPage;
import cloud.tobbbles.gielinor.runelite.v1.CombatAchievementTask;
import cloud.tobbbles.gielinor.runelite.v1.DiaryRegion;
import cloud.tobbbles.gielinor.runelite.v1.DiaryTier;
import cloud.tobbbles.gielinor.runelite.v1.Item;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.ObservationSource;
import cloud.tobbbles.gielinor.runelite.v1.Observed;
import cloud.tobbbles.gielinor.runelite.v1.Quest;
import cloud.tobbbles.gielinor.runelite.v1.Slayer;
import com.google.protobuf.MessageLite;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Reads the game into the {@link StateStore}. Every method must run on the client thread, where
 * the {@link Game} may be read. Each returns whether anything changed, so the caller knows to tell
 * the pusher.
 *
 * <p>Only the player's own state is read, and only the groups the player left on. Combat
 * achievements and the collection log catalog are large, so they are built only while the relay
 * says someone wants them ({@code PushResponse.wanted}).
 */
public class Collectors
{
	/** Groups a player can switch off, in the order the config lists them. */
	public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList(
		StateStore.SKILLS, StateStore.QUESTS, StateStore.DIARIES,
		StateStore.COMBAT_ACHIEVEMENTS, StateStore.COLLECTION_LOG, StateStore.BOSS_KC, StateStore.SLAYER,
		StateStore.INVENTORY, StateStore.EQUIPMENT, StateStore.BANK));

	/**
	 * The real skills: RuneLite's Skill enum, less the deprecated OVERALL, picked out by name so
	 * that the deprecated constant is never referenced.
	 */
	private static final Set<Skill> SKILLS;

	static
	{
		Set<Skill> skills = EnumSet.noneOf(Skill.class);
		for (Skill skill : Skill.values())
		{
			if (!"OVERALL".equals(skill.name()))
			{
				skills.add(skill);
			}
		}
		SKILLS = Collections.unmodifiableSet(skills);
	}

	private final Game game;
	private final StateStore store;
	private final Predicate<String> groupEnabled;
	private final Predicate<String> wanted;
	private final LongSupplier clock;
	private final CollectionLogReader collectionLog = new CollectionLogReader();
	private final BossKcReader bossKc = new BossKcReader();
	/**
	 * The last content written for paths whose message carries an Observed time, read with the
	 * time left out. Without it every read would differ (a new time) and push for nothing.
	 */
	private final Map<String, Object> content = new HashMap<>();

	/**
	 * @param groupEnabled whether the player left a group on
	 * @param wanted       whether the relay wants a hot field built (combat_achievements, collection_log)
	 */
	public Collectors(Game game, StateStore store, Predicate<String> groupEnabled, Predicate<String> wanted,
		LongSupplier clock)
	{
		this.game = game;
		this.store = store;
		this.groupEnabled = groupEnabled;
		this.wanted = wanted;
		this.clock = clock;
	}

	/** Another character, or a fresh start: nothing read before counts. */
	public void reset()
	{
		content.clear();
		collectionLog.reset();
	}

	/** Everything, as after logging in. */
	public boolean readAll()
	{
		boolean changed = applyGroups();
		changed |= readAccount();
		changed |= readSkills();
		changed |= readQuests();
		changed |= readDiaries();
		changed |= readCombatAchievements();
		changed |= readCollectionLog();
		changed |= readBossKc();
		changed |= readSlayer();
		changed |= readContainer(InventoryID.INV);
		changed |= readContainer(InventoryID.WORN);
		changed |= readContainer(InventoryID.BANK);
		return changed;
	}

	/** Clears each group the player switched off, so the relay forgets it too. */
	public boolean applyGroups()
	{
		boolean changed = false;
		for (String group : GROUPS)
		{
			if (!groupEnabled.test(group))
			{
				content.keySet().removeIf(path -> path.equals(group) || path.startsWith(group + "."));
				changed |= store.clearGroup(group);
			}
		}
		return changed;
	}

	// ---- account, skills, quests, diaries ----

	/** Account type, membership, combat level, totals and quest points. Always sent. */
	public boolean readAccount()
	{
		Account account = Account.newBuilder()
			.setObserved(observed(ObservationSource.OBSERVATION_SOURCE_VAR))
			.setAccountType(Convert.accountType(game.varbit(VarbitID.IRONMAN)))
			.setMembers(game.worldTypes().contains(WorldType.MEMBERS))
			.setCombatLevel(game.combatLevel())
			.setTotalLevel(game.totalLevel())
			.setTotalXp(game.overallExperience())
			.setQuestPoints(game.varp(VarPlayerID.QP))
			.build();
		return putObserved(StateStore.ACCOUNT, account,
			at -> account.toBuilder().setObserved(account.getObserved().toBuilder().setAtMs(at)).build());
	}

	public boolean readSkills()
	{
		boolean changed = false;
		for (Skill skill : SKILLS)
		{
			changed |= readSkill(skill);
		}
		return changed;
	}

	/** Whether a StatChanged is for a skill we send (every skill but the deprecated OVERALL). */
	public static boolean isSkill(Skill skill)
	{
		return SKILLS.contains(skill);
	}

	/** One skill's real level and XP; boosted levels change with every potion and aren't sent. */
	public boolean readSkill(Skill skill)
	{
		if (!isSkill(skill) || !groupEnabled.test(StateStore.SKILLS))
		{
			return false;
		}
		return store.put(StateStore.SKILLS + "." + skill.name(), cloud.tobbbles.gielinor.runelite.v1.Skill.newBuilder()
			.setLevel(game.realLevel(skill))
			.setXp(game.experience(skill))
			.build());
	}

	/** Every quest's state. Runs a client script per quest (~200), so not on every tick. */
	public boolean readQuests()
	{
		if (!groupEnabled.test(StateStore.QUESTS))
		{
			return false;
		}
		boolean changed = false;
		for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
		{
			changed |= store.put(StateStore.QUESTS + "." + quest.name(), Quest.newBuilder()
				.setName(quest.getName())
				.setState(Convert.questState(game.questState(quest)))
				.build());
		}
		return changed;
	}

	public boolean readDiaries()
	{
		if (!groupEnabled.test(StateStore.DIARIES))
		{
			return false;
		}
		boolean changed = false;
		for (Diaries.Region region : Diaries.REGIONS)
		{
			changed |= store.put(StateStore.DIARIES + "." + region.key, DiaryRegion.newBuilder()
				.setName(region.name)
				.setStarted(game.varbit(region.started) > 0)
				.setEasy(tier(region.tiers.get(0)))
				.setMedium(tier(region.tiers.get(1)))
				.setHard(tier(region.tiers.get(2)))
				.setElite(tier(region.tiers.get(3)))
				.build());
		}
		return changed;
	}

	/** Whether a varbit is one the diary table reads. */
	public static boolean isDiaryVarbit(int varbitId)
	{
		return Diaries.VARBITS.contains(varbitId);
	}

	private DiaryTier tier(Diaries.Tier tier)
	{
		return Convert.diaryTier(game.varbit(tier.complete), tier.completeAt, game.varbit(tier.tasks), game.varbit(tier.reward));
	}

	// ---- combat achievements ----

	/** Every task's completion, while the relay wants them. One map entry per task. */
	public boolean readCombatAchievements()
	{
		if (!groupEnabled.test(StateStore.COMBAT_ACHIEVEMENTS) || !wanted.test(StateStore.COMBAT_ACHIEVEMENTS))
		{
			return false;
		}
		boolean changed = false;
		for (Map.Entry<Integer, CombatAchievementTask> task : CombatAchievementsReader.read(game).entrySet())
		{
			changed |= store.put(StateStore.COMBAT_ACHIEVEMENTS + ".tasks." + task.getKey(), task.getValue());
		}
		if (changed)
		{
			store.put(StateStore.COMBAT_ACHIEVEMENTS + ".observed",
				observed(ObservationSource.OBSERVATION_SOURCE_VAR).toBuilder().setAtMs(clock.getAsLong()).build());
		}
		return changed;
	}

	/** Whether a varp is one of the combat achievement completion bitfields. */
	public static boolean isCombatAchievementVarp(int varpId)
	{
		return CombatAchievementsReader.isCompletionVarp(varpId);
	}

	// ---- collection log ----

	/**
	 * The unique counts, always; the catalog of pages, while the relay wants it. Items' states come
	 * only from pages the player opens ({@link #viewCollectionLogPage}).
	 */
	public boolean readCollectionLog()
	{
		if (!groupEnabled.test(StateStore.COLLECTION_LOG))
		{
			return false;
		}
		boolean changed = store.put(StateStore.COLLECTION_LOG + ".unique_obtained", game.varp(VarPlayerID.COLLECTION_COUNT));
		changed |= store.put(StateStore.COLLECTION_LOG + ".unique_total", game.varp(VarPlayerID.COLLECTION_COUNT_MAX));
		if (wanted.test(StateStore.COLLECTION_LOG))
		{
			for (Map.Entry<String, CollectionLogPage> page : collectionLog.catalog(game).entrySet())
			{
				String path = StateStore.COLLECTION_LOG + ".pages." + page.getKey();
				// A page opened this session already holds a fuller entry: keep it.
				if (store.get(path) == null)
				{
					changed |= store.put(path, page.getValue());
				}
			}
		}
		return stampCollectionLog(changed);
	}

	/** A collection log page on screen: its entry and every item's state on it. */
	public boolean viewCollectionLogPage(Game.CollectionLogPage page)
	{
		if (page == null || !groupEnabled.test(StateStore.COLLECTION_LOG))
		{
			return false;
		}
		CollectionLogReader.Viewed viewed = collectionLog.view(game, page);
		if (viewed == null)
		{
			return false;
		}
		boolean changed = store.put(StateStore.COLLECTION_LOG + ".pages." + viewed.key, viewed.page);
		for (Map.Entry<Integer, CollectionLogItem> item : viewed.items.entrySet())
		{
			changed |= store.put(StateStore.COLLECTION_LOG + ".items." + item.getKey(), item.getValue());
		}
		return stampCollectionLog(changed);
	}

	/** Whether a varp is one of the collection log's unique counts. */
	public static boolean isCollectionLogVarp(int varpId)
	{
		return varpId == VarPlayerID.COLLECTION_COUNT || varpId == VarPlayerID.COLLECTION_COUNT_MAX;
	}

	private boolean stampCollectionLog(boolean changed)
	{
		if (changed)
		{
			store.put(StateStore.COLLECTION_LOG + ".observed", Observed.newBuilder()
				.setKnown(true)
				.setSource(ObservationSource.OBSERVATION_SOURCE_INTERFACE)
				// Partial until every page has been opened this session.
				.setPartial(!collectionLog.complete(game))
				.setAtMs(clock.getAsLong())
				.build());
		}
		return changed;
	}

	// ---- bosses and Slayer ----

	/** Every boss's kill count. */
	public boolean readBossKc()
	{
		if (!groupEnabled.test(StateStore.BOSS_KC))
		{
			return false;
		}
		boolean changed = false;
		for (Map.Entry<String, BossRecord> boss : bossKc.read(game).entrySet())
		{
			BossRecord record = boss.getValue().toBuilder().setObserved(observed(ObservationSource.OBSERVATION_SOURCE_VAR)).build();
			changed |= putObserved(StateStore.BOSS_KC + "." + boss.getKey(), record,
				at -> record.toBuilder().setObserved(record.getObserved().toBuilder().setAtMs(at)).build());
		}
		return changed;
	}

	/** Whether a varp holds a boss's kill count (as of the last read). */
	public boolean isKillCountVarp(int varpId)
	{
		return bossKc.isKillCountVarp(varpId);
	}

	public boolean readSlayer()
	{
		if (!groupEnabled.test(StateStore.SLAYER))
		{
			return false;
		}
		Slayer slayer = SlayerReader.read(game).toBuilder().setObserved(observed(ObservationSource.OBSERVATION_SOURCE_VAR)).build();
		return putObserved(StateStore.SLAYER, slayer,
			at -> slayer.toBuilder().setObserved(slayer.getObserved().toBuilder().setAtMs(at)).build());
	}

	/** Whether a var change can move the Slayer task, points or streak. */
	public static boolean isSlayerVar(int varpId, int varbitId)
	{
		return SlayerReader.isSlayerVar(varpId, varbitId);
	}

	// ---- items ----

	/**
	 * The inventory, worn equipment or bank. A container the client doesn't hold (the bank before
	 * it has been opened this session) is unknown, and left as it was: unknown is never empty.
	 */
	public boolean readContainer(int containerId)
	{
		String path = containerPath(containerId);
		if (path == null || !groupEnabled.test(path))
		{
			return false;
		}
		List<Game.Slot> slots = game.container(containerId);
		if (slots == null)
		{
			return false;
		}

		Observed observed = observed(ObservationSource.OBSERVATION_SOURCE_CONTAINER);
		if (containerId == InventoryID.BANK)
		{
			Bank.Builder bank = Bank.newBuilder().setObserved(observed);
			for (Game.Slot slot : slots)
			{
				bank.addItems(item(slot).toBuilder().setBankSlot(slot.index));
			}
			Bank built = bank.build();
			return putObserved(path, built,
				at -> built.toBuilder().setObserved(built.getObserved().toBuilder().setAtMs(at)).build());
		}

		ItemContainer.Builder items = ItemContainer.newBuilder().setObserved(observed);
		for (Game.Slot slot : slots)
		{
			Item item = item(slot);
			items.addItems(containerId == InventoryID.WORN ? item.toBuilder().setSlot(Convert.equipmentSlot(slot.index)).build() : item);
		}
		ItemContainer built = items.build();
		return putObserved(path, built,
			at -> built.toBuilder().setObserved(built.getObserved().toBuilder().setAtMs(at)).build());
	}

	private static String containerPath(int containerId)
	{
		if (containerId == InventoryID.INV)
		{
			return StateStore.INVENTORY;
		}
		if (containerId == InventoryID.WORN)
		{
			return StateStore.EQUIPMENT;
		}
		if (containerId == InventoryID.BANK)
		{
			return StateStore.BANK;
		}
		return null;
	}

	private Item item(Game.Slot slot)
	{
		Game.ItemInfo info = game.item(slot.id);
		boolean placeholder = info.placeholderFor != -1;
		String name = placeholder ? game.item(info.placeholderFor).name : info.name;
		return Item.newBuilder()
			.setId(placeholder ? info.placeholderFor : slot.id)
			.setQty(placeholder ? 0 : slot.quantity)
			.setName(name != null ? name : "")
			.setPlaceholder(placeholder)
			.build();
	}

	// ---- helpers ----

	/** Known, from this source; the time is set only when the content changes. */
	private static Observed observed(ObservationSource source)
	{
		return Observed.newBuilder().setKnown(true).setSource(source).build();
	}

	private boolean putObserved(String path, MessageLite withoutTime, LongFunction<MessageLite> stamped)
	{
		if (withoutTime.equals(content.get(path)))
		{
			return false;
		}
		content.put(path, withoutTime);
		return store.put(path, stamped.apply(clock.getAsLong()));
	}
}
