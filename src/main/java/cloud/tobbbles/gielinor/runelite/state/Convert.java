package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.AccountType;
import cloud.tobbbles.gielinor.runelite.v1.DiaryTier;
import cloud.tobbbles.gielinor.runelite.v1.EquipmentSlot;
import cloud.tobbbles.gielinor.runelite.v1.QuestState;
import java.util.EnumSet;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/** RuneLite's values to the wire contract's. Pure, so it is tested without a game client. */
public final class Convert
{
	private Convert()
	{
	}

	/**
	 * The account type from the game's account-type varbit ({@code VarbitID.IRONMAN}, what the
	 * deprecated {@code Client.getAccountType()} reads): 0 normal, 1 ironman, 2 ultimate, 3
	 * hardcore, 4 group, 5 hardcore group, 6 unranked group. RuneLite's own enum stops at hardcore
	 * group, which is why the varbit is read directly. 1 to 3 are as RuneLite's Hiscore plugin
	 * reads them; the group values are the game's, unconfirmed by a RuneLite source.
	 */
	public static AccountType accountType(int varbit)
	{
		switch (varbit)
		{
			case 0:
				return AccountType.ACCOUNT_TYPE_NORMAL;
			case 1:
				return AccountType.ACCOUNT_TYPE_IRONMAN;
			case 2:
				return AccountType.ACCOUNT_TYPE_ULTIMATE_IRONMAN;
			case 3:
				return AccountType.ACCOUNT_TYPE_HARDCORE_IRONMAN;
			case 4:
				return AccountType.ACCOUNT_TYPE_GROUP_IRONMAN;
			case 5:
				return AccountType.ACCOUNT_TYPE_HARDCORE_GROUP_IRONMAN;
			case 6:
				return AccountType.ACCOUNT_TYPE_UNRANKED_GROUP_IRONMAN;
			default:
				return AccountType.ACCOUNT_TYPE_UNSPECIFIED;
		}
	}

	public static QuestState questState(net.runelite.api.QuestState state)
	{
		if (state == null)
		{
			return QuestState.QUEST_STATE_UNSPECIFIED;
		}
		switch (state)
		{
			case NOT_STARTED:
				return QuestState.QUEST_STATE_NOT_STARTED;
			case IN_PROGRESS:
				return QuestState.QUEST_STATE_IN_PROGRESS;
			case FINISHED:
				return QuestState.QUEST_STATE_FINISHED;
			default:
				return QuestState.QUEST_STATE_UNSPECIFIED;
		}
	}

	/** The equipment container's slot index to the contract's slot, by RuneLite's slot names. */
	public static EquipmentSlot equipmentSlot(int index)
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (slot.getSlotIdx() == index)
			{
				return EquipmentSlot.valueOf("EQUIPMENT_SLOT_" + slot.name());
			}
		}
		return EquipmentSlot.EQUIPMENT_SLOT_UNSPECIFIED;
	}

	/**
	 * Whether a character on this profile and world is pushed at all. Beta and PvP Arena profiles
	 * never are, and tournament worlds aren't either: RuneLite files them under STANDARD, so the
	 * world type has to be checked too.
	 */
	public static boolean tracked(RuneScapeProfileType profile, EnumSet<WorldType> world)
	{
		if (profile == null || profile == RuneScapeProfileType.BETA || profile == RuneScapeProfileType.PVP_ARENA)
		{
			return false;
		}
		return world == null || !(world.contains(WorldType.TOURNAMENT_WORLD) || world.contains(WorldType.BETA_WORLD));
	}

	/**
	 * A diary tier from its varbits. {@code completeAt} is the value meaning done: 1, except
	 * Karamja's easy to hard tiers, whose older varbits count 1 as in progress and 2 as done.
	 */
	public static DiaryTier diaryTier(int completeValue, int completeAt, int tasks, int reward)
	{
		return DiaryTier.newBuilder()
			.setComplete(completeValue >= completeAt)
			.setTasksComplete(Math.max(0, tasks))
			.setRewardClaimed(reward > 0)
			.build();
	}
}
