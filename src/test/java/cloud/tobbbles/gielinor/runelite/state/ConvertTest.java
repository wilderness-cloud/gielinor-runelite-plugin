package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.AccountType;
import cloud.tobbbles.gielinor.runelite.v1.DiaryTier;
import cloud.tobbbles.gielinor.runelite.v1.EquipmentSlot;
import cloud.tobbbles.gielinor.runelite.v1.QuestState;
import java.util.EnumSet;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ConvertTest
{
	@Test
	public void accountTypesFromTheVarbit()
	{
		assertEquals(AccountType.ACCOUNT_TYPE_NORMAL, Convert.accountType(0));
		assertEquals(AccountType.ACCOUNT_TYPE_IRONMAN, Convert.accountType(1));
		assertEquals(AccountType.ACCOUNT_TYPE_ULTIMATE_IRONMAN, Convert.accountType(2));
		assertEquals(AccountType.ACCOUNT_TYPE_HARDCORE_IRONMAN, Convert.accountType(3));
		assertEquals(AccountType.ACCOUNT_TYPE_UNRANKED_GROUP_IRONMAN, Convert.accountType(6));
		assertEquals(AccountType.ACCOUNT_TYPE_UNSPECIFIED, Convert.accountType(42));
	}

	@Test
	public void questStates()
	{
		assertEquals(QuestState.QUEST_STATE_FINISHED, Convert.questState(net.runelite.api.QuestState.FINISHED));
		assertEquals(QuestState.QUEST_STATE_IN_PROGRESS, Convert.questState(net.runelite.api.QuestState.IN_PROGRESS));
		assertEquals(QuestState.QUEST_STATE_NOT_STARTED, Convert.questState(net.runelite.api.QuestState.NOT_STARTED));
	}

	@Test
	public void everyEquipmentSlotHasAContractSlot()
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			EquipmentSlot converted = Convert.equipmentSlot(slot.getSlotIdx());
			assertEquals("EQUIPMENT_SLOT_" + slot.name(), converted.name());
		}
		assertEquals(EquipmentSlot.EQUIPMENT_SLOT_UNSPECIFIED, Convert.equipmentSlot(99));
	}

	@Test
	public void onlyRealProgressIsTracked()
	{
		EnumSet<WorldType> members = EnumSet.of(WorldType.MEMBERS);
		assertTrue(Convert.tracked(RuneScapeProfileType.STANDARD, members));
		assertTrue(Convert.tracked(RuneScapeProfileType.DEADMAN, EnumSet.of(WorldType.DEADMAN)));
		assertFalse(Convert.tracked(RuneScapeProfileType.BETA, members));
		assertFalse(Convert.tracked(RuneScapeProfileType.PVP_ARENA, members));
		assertFalse("tournament worlds file under STANDARD",
			Convert.tracked(RuneScapeProfileType.STANDARD, EnumSet.of(WorldType.TOURNAMENT_WORLD)));
		assertFalse(Convert.tracked(null, members));
	}

	@Test
	public void diaryTiers()
	{
		DiaryTier done = Convert.diaryTier(1, 1, 10, 1);
		assertTrue(done.getComplete());
		assertEquals(10, done.getTasksComplete());
		assertTrue(done.getRewardClaimed());

		// Karamja's old varbits: 1 is in progress, 2 is done.
		assertFalse(Convert.diaryTier(1, 2, 4, 0).getComplete());
		assertTrue(Convert.diaryTier(2, 2, 10, 0).getComplete());
	}

	@Test
	public void theDiaryTableCoversEveryRegionOnce()
	{
		assertEquals(12, Diaries.REGIONS.size());
		assertEquals(12, Diaries.REGIONS.stream().map(r -> r.key).distinct().count());
		for (Diaries.Region region : Diaries.REGIONS)
		{
			assertTrue(region.key, region.key.matches("[A-Z_]+"));
			assertEquals(4, region.tiers.size());
			assertNotEquals(region.tiers.get(0).complete, region.tiers.get(3).complete);
		}
		assertTrue(Collectors.isDiaryVarbit(Diaries.REGIONS.get(0).started));
		assertFalse(Collectors.isDiaryVarbit(-1));
	}

	@Test
	public void groupAccountTypesAndOddInputs()
	{
		assertEquals(cloud.tobbbles.gielinor.runelite.v1.AccountType.ACCOUNT_TYPE_GROUP_IRONMAN, Convert.accountType(4));
		assertEquals(cloud.tobbbles.gielinor.runelite.v1.AccountType.ACCOUNT_TYPE_HARDCORE_GROUP_IRONMAN, Convert.accountType(5));
		assertEquals(cloud.tobbbles.gielinor.runelite.v1.QuestState.QUEST_STATE_UNSPECIFIED, Convert.questState(null));
		assertTrue(Convert.tracked(net.runelite.client.config.RuneScapeProfileType.STANDARD, null));
		assertFalse(Convert.tracked(net.runelite.client.config.RuneScapeProfileType.STANDARD,
			java.util.EnumSet.of(net.runelite.api.WorldType.BETA_WORLD)));
	}
}
