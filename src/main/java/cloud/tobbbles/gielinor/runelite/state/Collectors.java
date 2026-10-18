package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.Account;
import cloud.tobbbles.gielinor.runelite.v1.Bank;
import cloud.tobbbles.gielinor.runelite.v1.DiaryRegion;
import cloud.tobbbles.gielinor.runelite.v1.Item;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.ObservationSource;
import cloud.tobbbles.gielinor.runelite.v1.Observed;
import cloud.tobbbles.gielinor.runelite.v1.Quest;
import com.google.protobuf.MessageLite;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Reads the game into the {@link StateStore}. Every method must run on the client thread: quest
 * states, varbits and item containers are only safe to read there. Each returns whether anything
 * changed, so the caller knows to tell the pusher.
 *
 * <p>Only the player's own state is read, and only the groups the player left on.
 */
public class Collectors
{
	/** Groups a player can switch off, in the order the config lists them. */
	public static final List<String> GROUPS = Arrays.asList(
		StateStore.SKILLS, StateStore.QUESTS, StateStore.DIARIES,
		StateStore.INVENTORY, StateStore.EQUIPMENT, StateStore.BANK);

	/**
	 * The real skills: RuneLite's Skill enum, less the deprecated OVERALL, picked out by name so
	 * that the deprecated constant is never referenced.
	 */
	private static final java.util.Set<Skill> SKILLS;

	static
	{
		java.util.Set<Skill> skills = java.util.EnumSet.noneOf(Skill.class);
		for (Skill skill : Skill.values())
		{
			if (!"OVERALL".equals(skill.name()))
			{
				skills.add(skill);
			}
		}
		SKILLS = java.util.Collections.unmodifiableSet(skills);
	}

	private final Client client;
	private final ItemManager items;
	private final StateStore store;
	private final Predicate<String> groupEnabled;
	private final LongSupplier clock;
	/**
	 * The last content written for paths whose message carries an Observed time, read with the
	 * time left out. Without it every read would differ (a new time) and push for nothing.
	 */
	private final Map<String, MessageLite> content = new HashMap<>();

	public Collectors(Client client, ItemManager items, StateStore store, Predicate<String> groupEnabled,
		LongSupplier clock)
	{
		this.client = client;
		this.items = items;
		this.store = store;
		this.groupEnabled = groupEnabled;
		this.clock = clock;
	}

	/** Another character, or a fresh start: nothing read before counts. */
	public void reset()
	{
		content.clear();
	}

	/** Everything, as after logging in. */
	public boolean readAll()
	{
		boolean changed = applyGroups();
		changed |= readAccount();
		changed |= readSkills();
		changed |= readQuests();
		changed |= readDiaries();
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

	/** Account type, membership, combat level, totals and quest points. Always sent. */
	public boolean readAccount()
	{
		Player me = client.getLocalPlayer();
		Account account = Account.newBuilder()
			.setObserved(observed(ObservationSource.OBSERVATION_SOURCE_VAR))
			.setAccountType(Convert.accountType(client.getVarbitValue(VarbitID.IRONMAN)))
			.setMembers(client.getWorldType().contains(WorldType.MEMBERS))
			.setCombatLevel(me != null ? me.getCombatLevel() : 0)
			.setTotalLevel(client.getTotalLevel())
			.setTotalXp(client.getOverallExperience())
			.setQuestPoints(client.getVarpValue(VarPlayerID.QP))
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
			.setLevel(client.getRealSkillLevel(skill))
			.setXp(client.getSkillExperience(skill))
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
				.setState(Convert.questState(quest.getState(client)))
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
			DiaryRegion.Builder r = DiaryRegion.newBuilder()
				.setName(region.name)
				.setStarted(client.getVarbitValue(region.started) > 0);
			Diaries.Tier[] tiers = region.tiers.toArray(new Diaries.Tier[0]);
			r.setEasy(tier(tiers[0])).setMedium(tier(tiers[1])).setHard(tier(tiers[2])).setElite(tier(tiers[3]));
			changed |= store.put(StateStore.DIARIES + "." + region.key, r.build());
		}
		return changed;
	}

	/** Whether a varbit is one the diary table reads. */
	public static boolean isDiaryVarbit(int varbitId)
	{
		return Diaries.VARBITS.contains(varbitId);
	}

	/**
	 * The inventory, worn equipment or bank. A container the client doesn't hold (the bank before
	 * it has been opened this session) is unknown, and left as it was: unknown is never empty.
	 */
	public boolean readContainer(int containerId)
	{
		String path;
		if (containerId == InventoryID.INV)
		{
			path = StateStore.INVENTORY;
		}
		else if (containerId == InventoryID.WORN)
		{
			path = StateStore.EQUIPMENT;
		}
		else if (containerId == InventoryID.BANK)
		{
			path = StateStore.BANK;
		}
		else
		{
			return false;
		}
		if (!groupEnabled.test(path))
		{
			return false;
		}
		net.runelite.api.ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return false;
		}

		Observed observed = observed(ObservationSource.OBSERVATION_SOURCE_CONTAINER);
		net.runelite.api.Item[] slots = container.getItems();
		if (containerId == InventoryID.BANK)
		{
			Bank.Builder bank = Bank.newBuilder().setObserved(observed);
			for (int i = 0; i < slots.length; i++)
			{
				Item item = item(slots[i]);
				if (item != null)
				{
					bank.addItems(item.toBuilder().setBankSlot(i));
				}
			}
			Bank built = bank.build();
			return putObserved(path, built,
				at -> built.toBuilder().setObserved(built.getObserved().toBuilder().setAtMs(at)).build());
		}

		ItemContainer.Builder items = ItemContainer.newBuilder().setObserved(observed);
		boolean worn = containerId == InventoryID.WORN;
		for (int i = 0; i < slots.length; i++)
		{
			Item item = item(slots[i]);
			if (item != null)
			{
				items.addItems(worn ? item.toBuilder().setSlot(Convert.equipmentSlot(i)).build() : item);
			}
		}
		ItemContainer built = items.build();
		return putObserved(path, built,
			at -> built.toBuilder().setObserved(built.getObserved().toBuilder().setAtMs(at)).build());
	}

	private Item item(net.runelite.api.Item slot)
	{
		if (slot == null || slot.getId() <= 0)
		{
			return null;
		}
		ItemComposition composition = items.getItemComposition(slot.getId());
		boolean placeholder = composition.getPlaceholderTemplateId() != -1;
		int id = placeholder ? composition.getPlaceholderId() : slot.getId();
		String name = placeholder ? items.getItemComposition(id).getName() : composition.getName();
		return Item.newBuilder()
			.setId(id)
			.setQty(placeholder ? 0 : slot.getQuantity())
			.setName(name != null ? name : "")
			.setPlaceholder(placeholder)
			.build();
	}

	private cloud.tobbbles.gielinor.runelite.v1.DiaryTier tier(Diaries.Tier tier)
	{
		return Convert.diaryTier(client.getVarbitValue(tier.complete), tier.completeAt,
			client.getVarbitValue(tier.tasks), client.getVarbitValue(tier.reward));
	}

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
