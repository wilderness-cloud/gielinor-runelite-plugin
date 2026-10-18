package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.FakeGame;
import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.Account;
import cloud.tobbbles.gielinor.runelite.v1.AccountType;
import cloud.tobbbles.gielinor.runelite.v1.Bank;
import cloud.tobbbles.gielinor.runelite.v1.CharacterState;
import cloud.tobbbles.gielinor.runelite.v1.DiaryRegion;
import cloud.tobbbles.gielinor.runelite.v1.EquipmentSlot;
import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.ObservationSource;
import cloud.tobbbles.gielinor.runelite.v1.QuestState;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class CollectorsTest
{
	private FakeGame game;
	private StateStore store;
	private Set<String> off;
	private long now;
	private Collectors collectors;

	@Before
	public void setUp()
	{
		game = new FakeGame();
		store = new StateStore();
		off = new HashSet<>();
		now = 1_760_000_000_000L;
		collectors = new Collectors(game, store, g -> !off.contains(g), () -> now);
	}

	/** What a push would carry now; clears the store's changes as an acknowledgement would. */
	private CharacterState pushed()
	{
		StateStore.Snapshot snapshot = store.snapshot();
		store.acknowledged(snapshot);
		return snapshot.state;
	}

	// ---- account, skills, quests, diaries ----

	@Test
	public void readsTheAccount()
	{
		game.varbit(VarbitID.IRONMAN, 2).varp(VarPlayerID.QP, 150);
		assertTrue(collectors.readAccount());

		Account account = pushed().getAccount();
		assertEquals(AccountType.ACCOUNT_TYPE_ULTIMATE_IRONMAN, account.getAccountType());
		assertTrue(account.getMembers());
		assertEquals(100, account.getCombatLevel());
		assertEquals(1500, account.getTotalLevel());
		assertEquals(50_000_000L, account.getTotalXp());
		assertEquals(150, account.getQuestPoints());
		assertTrue(account.getObserved().getKnown());
		assertEquals(ObservationSource.OBSERVATION_SOURCE_VAR, account.getObserved().getSource());
		assertEquals(now, account.getObserved().getAtMs());
	}

	@Test
	public void theObservedTimeMovesOnlyWithTheContent()
	{
		collectors.readAccount();
		pushed();

		now += 60_000;
		assertFalse("same content, later read: no change", collectors.readAccount());
		assertFalse(store.hasChanges());

		game.worldTypes = java.util.EnumSet.noneOf(WorldType.class);
		assertTrue(collectors.readAccount());
		Account account = pushed().getAccount();
		assertFalse(account.getMembers());
		assertEquals(now, account.getObserved().getAtMs());

		// A reset forgets what was read, so the next read writes again.
		collectors.reset();
		assertFalse("the value itself is the same", collectors.readAccount());
	}

	@Test
	public void readsRealLevelsForEverySkillButOverall()
	{
		game.skill(Skill.ATTACK, 70, 737_627).skill(Skill.SAILING, 30, 13_363);
		assertTrue(collectors.readSkills());

		CharacterState state = pushed();
		assertEquals(70, state.getSkillsOrThrow("ATTACK").getLevel());
		assertEquals(13_363, state.getSkillsOrThrow("SAILING").getXp());
		assertFalse(state.getSkillsMap().containsKey("OVERALL"));
		long real = Arrays.stream(Skill.values()).filter(s -> !"OVERALL".equals(s.name())).count();
		assertEquals(real, state.getSkillsCount());
		assertTrue(Collectors.isSkill(Skill.ATTACK));
	}

	@Test
	public void readsEveryQuest()
	{
		game.quests.put(Quest.DRAGON_SLAYER_II, net.runelite.api.QuestState.FINISHED);
		assertTrue(collectors.readQuests());

		CharacterState state = pushed();
		assertEquals(Quest.values().length, state.getQuestsCount());
		assertEquals(QuestState.QUEST_STATE_FINISHED, state.getQuestsOrThrow("DRAGON_SLAYER_II").getState());
		assertEquals(Quest.DRAGON_SLAYER_II.getName(), state.getQuestsOrThrow("DRAGON_SLAYER_II").getName());
		assertEquals(QuestState.QUEST_STATE_NOT_STARTED, state.getQuestsOrThrow("COOKS_ASSISTANT").getState());
	}

	@Test
	public void readsTheDiaries()
	{
		Diaries.Region ardougne = Diaries.REGIONS.get(0);
		game.varbit(ardougne.started, 1)
			.varbit(ardougne.tiers.get(0).complete, 1)
			.varbit(ardougne.tiers.get(0).tasks, 10)
			.varbit(ardougne.tiers.get(0).reward, 1)
			.varbit(ardougne.tiers.get(1).tasks, 3);
		assertTrue(collectors.readDiaries());

		DiaryRegion region = pushed().getDiariesOrThrow("ARDOUGNE");
		assertEquals("Ardougne", region.getName());
		assertTrue(region.getStarted());
		assertTrue(region.getEasy().getComplete());
		assertTrue(region.getEasy().getRewardClaimed());
		assertFalse(region.getMedium().getComplete());
		assertEquals(3, region.getMedium().getTasksComplete());
		assertTrue(Collectors.isDiaryVarbit(ardougne.started));
	}

	// ---- items ----

	@Test
	public void readsTheInventoryAndWornItems()
	{
		game.item(4151, "Abyssal whip").item(995, "Coins")
			.container(InventoryID.INV, new Game.Slot(0, 995, 25_000))
			.container(InventoryID.WORN, new Game.Slot(3, 4151, 1));
		assertTrue(collectors.readContainer(InventoryID.INV));
		assertTrue(collectors.readContainer(InventoryID.WORN));

		CharacterState state = pushed();
		ItemContainer inventory = state.getInventory();
		assertEquals(995, inventory.getItems(0).getId());
		assertEquals(25_000, inventory.getItems(0).getQty());
		assertEquals("Coins", inventory.getItems(0).getName());
		assertEquals(ObservationSource.OBSERVATION_SOURCE_CONTAINER, inventory.getObserved().getSource());
		assertEquals(EquipmentSlot.EQUIPMENT_SLOT_WEAPON, state.getEquipment().getItems(0).getSlot());
	}

	@Test
	public void bankPlaceholdersAreTheRealItemWithNoneHeld()
	{
		game.item(11832, "Bandos chestplate").placeholder(20000, 11832).item(995, "Coins")
			.container(InventoryID.BANK, new Game.Slot(0, 995, 1_000_000), new Game.Slot(5, 20000, 1));
		assertTrue(collectors.readContainer(InventoryID.BANK));

		Bank bank = pushed().getBank();
		assertEquals(2, bank.getItemsCount());
		assertEquals(0, bank.getItems(0).getBankSlot());
		assertEquals(11832, bank.getItems(1).getId());
		assertEquals("Bandos chestplate", bank.getItems(1).getName());
		assertTrue(bank.getItems(1).getPlaceholder());
		assertEquals(0, bank.getItems(1).getQty());
		assertEquals(5, bank.getItems(1).getBankSlot());
	}

	@Test
	public void aBankNotYetOpenedIsUnknownNotEmpty()
	{
		assertFalse(collectors.readContainer(InventoryID.BANK));
		assertFalse(store.hasChanges());
		assertFalse("another container isn't ours to read", collectors.readContainer(93_000));
	}

	@Test
	public void aNamelessItemStillHasAName()
	{
		game.items.put(1, new Game.ItemInfo(null, -1));
		game.container(InventoryID.INV, new Game.Slot(0, 1, 1));
		collectors.readContainer(InventoryID.INV);
		assertEquals("", pushed().getInventory().getItems(0).getName());
	}

	// ---- groups ----

	@Test
	public void aGroupSwitchedOffIsNotReadAndIsCleared()
	{
		game.skill(Skill.ATTACK, 70, 737_627).container(InventoryID.BANK, new Game.Slot(0, 995, 1));
		collectors.readAll();
		pushed();

		off.addAll(Arrays.asList(StateStore.SKILLS, StateStore.BANK));
		assertTrue(collectors.applyGroups());
		StateStore.Snapshot cleared = store.snapshot();
		assertTrue(cleared.mask.getPathsList().contains("bank"));
		assertTrue(cleared.mask.getPathsList().contains("skills.ATTACK"));
		assertFalse(cleared.state.hasBank());
		store.acknowledged(cleared);

		assertFalse(collectors.readSkill(Skill.ATTACK));
		assertFalse(collectors.readContainer(InventoryID.BANK));
		assertFalse(collectors.readQuests() || collectors.readDiaries() || collectors.readCombatAchievements()
			|| collectors.readCollectionLog() || collectors.readBossKc() || collectors.readSlayer());
	}

	@Test
	public void switchingAGroupBackOnReadsItAgain()
	{
		game.container(InventoryID.BANK, new Game.Slot(0, 995, 1));
		collectors.readContainer(InventoryID.BANK);
		pushed();
		off.add(StateStore.BANK);
		collectors.applyGroups();
		pushed();

		off.clear();
		assertTrue(collectors.readContainer(InventoryID.BANK));
		assertTrue(pushed().hasBank());
	}

	// ---- combat achievements ----

	private void combatAchievementTables()
	{
		game.enumOf(CombatAchievementsReader.TIER_ENUMS[0], 900, 901, 902)
			.struct(900, CombatAchievementsReader.TASK_ID_PARAM, 0)
			.struct(900, CombatAchievementsReader.TASK_NAME_PARAM, "Noxious Foe")
			.struct(901, CombatAchievementsReader.TASK_ID_PARAM, 33)
			.struct(901, CombatAchievementsReader.TASK_NAME_PARAM, "Barrows Novice")
			// A struct with no name is skipped.
			.struct(902, CombatAchievementsReader.TASK_ID_PARAM, 34)
			.enumOf(CombatAchievementsReader.TIER_ENUMS[5], 910)
			.struct(910, CombatAchievementsReader.TASK_ID_PARAM, 640)
			.struct(910, CombatAchievementsReader.TASK_NAME_PARAM, "Perfect Olm")
			// Task 0 done (bit 0 of varp 0); task 33 not (bit 1 of varp 1).
			.varp(VarPlayerID.CA_TASK_COMPLETED_0, 1);
	}

	@Test
	public void everyCombatAchievementIsSentWithWhenItWasRead()
	{
		combatAchievementTables();
		assertTrue(collectors.readCombatAchievements());

		CharacterState state = pushed();
		assertEquals(3, state.getCombatAchievements().getTasksCount());
		assertTrue(state.getCombatAchievements().getTasksOrThrow(0).getCompleted());
		assertEquals("EASY", state.getCombatAchievements().getTasksOrThrow(0).getTier());
		assertFalse(state.getCombatAchievements().getTasksOrThrow(33).getCompleted());
		assertEquals("GRANDMASTER", state.getCombatAchievements().getTasksOrThrow(640).getTier());
		assertEquals(now, state.getCombatAchievements().getObserved().getAtMs());
	}

	@Test
	public void aCompletedTaskSendsOneEntry()
	{
		combatAchievementTables();
		collectors.readCombatAchievements();
		pushed();

		assertFalse(collectors.readCombatAchievements());
		now += 60_000;
		game.varp(VarPlayerID.CA_TASK_COMPLETED_1, 0b10);
		assertTrue(collectors.readCombatAchievements());

		StateStore.Snapshot snapshot = store.snapshot();
		assertEquals(Arrays.asList("combat_achievements.observed", "combat_achievements.tasks.33"),
			snapshot.mask.getPathsList());
		assertTrue(Collectors.isCombatAchievementVarp(VarPlayerID.CA_TASK_COMPLETED_1));
		assertFalse(Collectors.isCombatAchievementVarp(VarPlayerID.QP));
	}

	@Test
	public void aTaskIdBeyondTheBitfieldsIsNotCompleted()
	{
		game.varp(VarPlayerID.CA_TASK_COMPLETED_0, -1);
		assertFalse(CombatAchievementsReader.completed(game, 32 * 21));
		assertTrue(CombatAchievementsReader.completed(game, 31));
	}

	// ---- collection log ----

	private void collectionLogTables()
	{
		game.struct(CollectionLogReader.TAB_STRUCTS[0], CollectionLogReader.TAB_NAME_PARAM, "Bosses")
			.struct(CollectionLogReader.TAB_STRUCTS[0], CollectionLogReader.TAB_PAGES_ENUM_PARAM, 5000)
			.enumOf(5000, 700, 701, 702)
			.struct(700, CollectionLogReader.PAGE_NAME_PARAM, "Vorkath")
			.struct(700, CollectionLogReader.PAGE_ITEMS_ENUM_PARAM, 5001)
			.enumOf(5001, 22006, 21907)
			.struct(701, CollectionLogReader.PAGE_NAME_PARAM, "Phosani's Nightmare")
			// Page 702 has no name and is skipped; page 701 lists no items.
			.struct(CollectionLogReader.TAB_STRUCTS[1], CollectionLogReader.TAB_NAME_PARAM, "Raids")
			.struct(CollectionLogReader.TAB_STRUCTS[1], CollectionLogReader.TAB_PAGES_ENUM_PARAM, 5002)
			.item(22006, "Skeletal visage").item(21907, "Vorki")
			.varp(VarPlayerID.COLLECTION_COUNT, 300)
			.varp(VarPlayerID.COLLECTION_COUNT_MAX, 1600);
	}

	@Test
	public void sendsTheUniqueCountsAndThePageList()
	{
		collectionLogTables();
		assertTrue(collectors.readCollectionLog());
		CharacterState state = pushed();
		assertEquals(300, state.getCollectionLog().getUniqueObtained());
		assertEquals(1600, state.getCollectionLog().getUniqueTotal());
		assertTrue("no page opened yet", state.getCollectionLog().getObserved().getPartial());
		assertEquals(2, state.getCollectionLog().getPagesCount());
		assertEquals("BOSSES", state.getCollectionLog().getPagesOrThrow("VORKATH").getTab());
		assertEquals(Arrays.asList(22006, 21907), state.getCollectionLog().getPagesOrThrow("VORKATH").getItemIdsList());
		assertTrue(state.getCollectionLog().getPagesMap().containsKey("PHOSANIS_NIGHTMARE"));
		assertEquals("items are only ever sent as seen", 0, state.getCollectionLog().getItemsCount());
		assertTrue(Collectors.isCollectionLogVarp(VarPlayerID.COLLECTION_COUNT));
		assertFalse(Collectors.isCollectionLogVarp(VarPlayerID.QP));
	}

	@Test
	public void aViewedPageSendsEachItemsState()
	{
		collectionLogTables();
		Game.CollectionLogPage page = new Game.CollectionLogPage("Vorkath", Arrays.asList(
			new Game.CollectionLogEntry(22006, true, 1), new Game.CollectionLogEntry(21907, false, 0)));
		assertTrue(collectors.viewCollectionLogPage(page));

		CharacterState state = pushed();
		assertTrue(state.getCollectionLog().getItemsOrThrow(22006).getObtained());
		assertEquals("Skeletal visage", state.getCollectionLog().getItemsOrThrow(22006).getName());
		assertFalse(state.getCollectionLog().getItemsOrThrow(21907).getObtained());
		assertTrue(state.getCollectionLog().getItemsOrThrow(21907).hasObtained());
		assertTrue(state.getCollectionLog().getPagesOrThrow("VORKATH").getViewedThisSession());
		assertEquals("BOSSES", state.getCollectionLog().getPagesOrThrow("VORKATH").getTab());
		assertTrue("one of two pages seen", state.getCollectionLog().getObserved().getPartial());

		// The catalog doesn't overwrite a page opened this session.
		collectors.readCollectionLog();
		assertTrue(pushed().getCollectionLog().getPagesMap().containsKey("PHOSANIS_NIGHTMARE"));
		assertTrue(((cloud.tobbbles.gielinor.runelite.v1.CollectionLogPage) store.get("collection_log.pages.VORKATH"))
			.getViewedThisSession());

		// Opening the last page completes the log.
		collectors.viewCollectionLogPage(new Game.CollectionLogPage("Phosani's Nightmare", Arrays.asList()));
		assertFalse(pushed().getCollectionLog().getObserved().getPartial());
	}

	@Test
	public void aPageMissingFromTheCatalogStillRecordsItsItems()
	{
		Game.CollectionLogPage page = new Game.CollectionLogPage("New boss", Arrays.asList(
			new Game.CollectionLogEntry(30000, true, 2)));
		assertTrue(collectors.viewCollectionLogPage(page));

		CharacterState state = pushed();
		assertEquals(Arrays.asList(30000), state.getCollectionLog().getPagesOrThrow("NEW_BOSS").getItemIdsList());
		assertEquals(2, state.getCollectionLog().getItemsOrThrow(30000).getQty());
		assertTrue("an empty catalog is never complete", state.getCollectionLog().getObserved().getPartial());
	}

	@Test
	public void nothingOnScreenOrANamelessPageIsNoChange()
	{
		assertFalse(collectors.viewCollectionLogPage(null));
		assertFalse(collectors.viewCollectionLogPage(new Game.CollectionLogPage("  ", Arrays.asList())));
		game.items.put(5, null);
		assertTrue(collectors.viewCollectionLogPage(new Game.CollectionLogPage("Other",
			Arrays.asList(new Game.CollectionLogEntry(5, false, 0)))));
		assertEquals("", pushed().getCollectionLog().getItemsOrThrow(5).getName());
	}

	// ---- bosses ----

	@Test
	public void readsEachBossKillCount()
	{
		int table = net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.ID;
		int name = net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.COL_BOSSNAME;
		int varp = net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.COL_BOSSVARP;
		game.dbRow(table, 1, name, "Vorkath", varp, 4000)
			.dbRow(table, 2, name, "Chambers of Xeric: Challenge Mode", varp, 4001)
			.dbRow(table, 3, name, "Broken row")
			.varp(4000, 512);
		assertTrue(collectors.readBossKc());

		CharacterState state = pushed();
		assertEquals(512, state.getBossKcOrThrow("VORKATH").getKills());
		assertEquals("Vorkath", state.getBossKcOrThrow("VORKATH").getName());
		assertEquals(0, state.getBossKcOrThrow("CHAMBERS_OF_XERIC_CHALLENGE_MODE").getKills());
		assertEquals(2, state.getBossKcCount());
		assertTrue(collectors.isKillCountVarp(4000));
		assertFalse(collectors.isKillCountVarp(4002));

		assertFalse(collectors.readBossKc());
		game.varp(4000, 513);
		assertTrue(collectors.readBossKc());
		assertEquals(Arrays.asList("boss_kc.VORKATH"), store.snapshot().mask.getPathsList());
	}

	// ---- Slayer ----

	private void slayerTables()
	{
		int task = net.runelite.api.gameval.DBTableID.SlayerTask.ID;
		int area = net.runelite.api.gameval.DBTableID.SlayerArea.ID;
		int sublist = net.runelite.api.gameval.DBTableID.SlayerTaskSublist.ID;
		game.dbRow(task, 10, net.runelite.api.gameval.DBTableID.SlayerTask.COL_ID, 42,
				net.runelite.api.gameval.DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Abyssal demons")
			.dbRow(task, 11, net.runelite.api.gameval.DBTableID.SlayerTask.COL_ID, 900,
				net.runelite.api.gameval.DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Vorkath")
			.dbRow(area, 20, net.runelite.api.gameval.DBTableID.SlayerArea.COL_AREA_ID, 7,
				net.runelite.api.gameval.DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, "the Catacombs of Kourend")
			.dbRow(sublist, 30, net.runelite.api.gameval.DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 5,
				net.runelite.api.gameval.DBTableID.SlayerTaskSublist.COL_TASK, 11);
	}

	@Test
	public void readsTheSlayerTask()
	{
		slayerTables();
		game.varp(VarPlayerID.SLAYER_COUNT, 80)
			.varp(VarPlayerID.SLAYER_TARGET, 42)
			.varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, 120)
			.varp(VarPlayerID.SLAYER_AREA, 7)
			.varbit(VarbitID.SLAYER_POINTS, 1_234)
			.varbit(VarbitID.SLAYER_TASKS_COMPLETED, 37);
		assertTrue(collectors.readSlayer());

		cloud.tobbbles.gielinor.runelite.v1.Slayer slayer = pushed().getSlayer();
		assertEquals("Abyssal demons", slayer.getTask().getName());
		assertEquals(80, slayer.getTask().getRemaining());
		assertEquals(120, slayer.getTask().getAmount());
		assertEquals("the Catacombs of Kourend", slayer.getTask().getLocation());
		assertEquals(1_234, slayer.getPoints());
		assertEquals(37, slayer.getStreak());
		assertTrue(Collectors.isSlayerVar(VarPlayerID.SLAYER_COUNT, -1));
		assertTrue(Collectors.isSlayerVar(-1, VarbitID.SLAYER_POINTS));
		assertFalse(Collectors.isSlayerVar(VarPlayerID.QP, VarbitID.IRONMAN));
	}

	@Test
	public void aBossTaskIsNamedThroughItsSublist()
	{
		slayerTables();
		game.varp(VarPlayerID.SLAYER_COUNT, 5)
			.varp(VarPlayerID.SLAYER_TARGET, SlayerReader.BOSS_TASK)
			.varbit(VarbitID.SLAYER_TARGET_BOSSID, 5);
		assertEquals("Vorkath", SlayerReader.read(game).getTask().getName());

		game.varbit(VarbitID.SLAYER_TARGET_BOSSID, 6);
		assertEquals("", SlayerReader.read(game).getTask().getName());
	}

	@Test
	public void noTaskAndUnknownNamesLeaveFieldsUnset()
	{
		slayerTables();
		assertFalse(SlayerReader.read(game).hasTask());

		game.varp(VarPlayerID.SLAYER_COUNT, 5).varp(VarPlayerID.SLAYER_TARGET, 1).varp(VarPlayerID.SLAYER_AREA, 99);
		cloud.tobbbles.gielinor.runelite.v1.SlayerTask task = SlayerReader.read(game).getTask();
		assertEquals("", task.getName());
		assertEquals("", task.getLocation());
		assertNull(SlayerReader.areaName(game, 0));
	}

	@Test
	public void theStreakIsTheMastersOwnAndModifiersChangeTheAmount()
	{
		game.varbit(VarbitID.SLAYER_TASKS_COMPLETED, 10)
			.varbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 20)
			.varp(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, 30);
		assertEquals(10, SlayerReader.streak(game));
		game.varbit(VarbitID.SLAYER_MASTER, SlayerReader.KRYSTILIA);
		assertEquals(20, SlayerReader.streak(game));
		game.varbit(VarbitID.SLAYER_MASTER, SlayerReader.MORTIMER);
		assertEquals(30, SlayerReader.streak(game));

		game.varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, 100);
		assertEquals(100, SlayerReader.initialAmount(game));
		game.varbit(VarbitID.SLAYER_MODIFIER_ID, SlayerReader.AMOUNT_MODIFIER).varbit(VarbitID.SLAYER_MODIFIER_VALUE, 15);
		assertEquals(115, SlayerReader.initialAmount(game));
		game.varbit(VarbitID.SLAYER_MODIFIER_NEGATIVE, 1);
		assertEquals(85, SlayerReader.initialAmount(game));
	}

	// ---- everything ----

	@Test
	public void readAllReadsEveryGroup()
	{
		combatAchievementTables();
		collectionLogTables();
		game.container(InventoryID.INV, new Game.Slot[0])
			.container(InventoryID.WORN, new Game.Slot[0])
			.container(InventoryID.BANK, new Game.Slot[0]);
		assertTrue(collectors.readAll());

		CharacterState state = pushed();
		assertTrue(state.hasAccount());
		assertTrue(state.getSkillsCount() > 0);
		assertTrue(state.getQuestsCount() > 0);
		assertEquals(12, state.getDiariesCount());
		assertTrue(state.hasCombatAchievements());
		assertTrue(state.hasCollectionLog());
		assertTrue(state.hasSlayer());
		assertTrue(state.hasInventory());
		assertTrue(state.hasEquipment());
		assertTrue(state.hasBank());
		assertFalse("nothing changed since", collectors.readAll());
	}
}
