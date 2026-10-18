package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.game.FakeGame;
import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.relay.FakeCredentials;
import cloud.tobbbles.gielinor.runelite.relay.FakeTransport;
import cloud.tobbbles.gielinor.runelite.relay.Pusher;
import cloud.tobbbles.gielinor.runelite.state.Collectors;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import cloud.tobbbles.gielinor.runelite.v1.PushRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class GameSessionTest
{
	private FakeGame game;
	private FakeScheduler scheduler;
	private FakeTransport transport;
	private Pusher pusher;
	private Set<String> off;
	private boolean sending;
	private GameSession session;

	@Before
	public void setUp()
	{
		game = new FakeGame();
		scheduler = new FakeScheduler();
		transport = new FakeTransport();
		off = new HashSet<>();
		StateStore store = new StateStore();
		pusher = new Pusher(store, transport, new FakeCredentials(), scheduler, scheduler::now, "install-1",
			PluginBuild.newBuilder().setVersion("test").build());
		Collectors collectors = new Collectors(game, store, g -> !off.contains(g), scheduler::now);
		session = new GameSession(game, collectors, pusher, () -> sending);
		sending = true;
		pusher.setEnabled(true);
	}

	/** Logged in and the first push answered. */
	private void loggedIn()
	{
		session.loggedIn();
		session.tick();
		scheduler.runDue();
		transport.ok();
	}

	/** Lets the throttle pass and returns what went out, answering it. */
	private PushRequest nextPush()
	{
		scheduler.advance(5_000);
		PushRequest push = transport.last();
		transport.ok();
		return push;
	}

	private List<String> nextMask()
	{
		int before = transport.pushes.size();
		PushRequest push = nextPush();
		assertEquals("one more push", before + 1, transport.pushes.size());
		return push.getUpdateMask().getPathsList();
	}

	@Test
	public void loggingInPushesTheCharacterWithEverythingRead()
	{
		session.loggedIn();
		assertFalse(session.tracked());
		session.tick();
		assertTrue(session.tracked());
		scheduler.runDue();

		PushRequest first = transport.last();
		assertEquals("Zezima", first.getCharacter().getRsn());
		assertEquals(302, first.getCharacter().getWorld());
		assertEquals("STANDARD", first.getCharacter().getProfile());
		assertTrue(first.getState().hasAccount());
		assertTrue(first.getState().getSkillsCount() > 0);
	}

	@Test
	public void waitsForThePlayerToLoad()
	{
		game.playerName = null;
		session.loggedIn();
		session.tick();
		assertFalse(session.tracked());
		game.playerName = "Zezima";
		session.tick();
		assertTrue(session.tracked());
	}

	@Test
	public void nothingHappensWhileNotSending()
	{
		sending = false;
		session.loggedIn();
		session.tick();
		assertFalse(session.tracked());
		session.statChanged(Skill.ATTACK);
		session.containerChanged(InventoryID.INV);
		session.collectionLogDrawn();
		session.groupsChanged();
		scheduler.advance(60_000);
		assertEquals(0, transport.pushes.size());
	}

	@Test
	public void anUntrackedWorldIsTreatedAsLoggedOut()
	{
		game.worldTypes = EnumSet.of(WorldType.TOURNAMENT_WORLD);
		session.loggedIn();
		session.tick();
		assertFalse(session.tracked());
		session.varChanged(VarPlayerID.QP, -1);
		session.tick();
		scheduler.advance(60_000);
		assertEquals(0, transport.pushes.size());
	}

	@Test
	public void leavingTheGameSendsOneLogoutPush()
	{
		loggedIn();
		session.leftGame();
		assertFalse(session.tracked());
		scheduler.runDue();
		assertFalse(transport.last().getCharacter().getLoggedIn());
	}

	@Test
	public void sendingSwitchedOnWhileLoggedInStartsAfresh()
	{
		sending = false;
		session.sendingChanged(true, false);
		session.tick();
		assertFalse("not logged in: nothing pending", session.tracked());

		sending = true;
		session.sendingChanged(true, true);
		session.tick();
		assertTrue(session.tracked());

		session.sendingChanged(false, true);
		assertFalse(session.tracked());
	}

	@Test
	public void aSkillChangeIsReadAtOnceAndTheTotalsNextTick()
	{
		loggedIn();
		game.skill(Skill.ATTACK, 2, 100);
		session.statChanged(Skill.ATTACK);
		game.totalLevel = 1501;
		session.tick();
		List<String> mask = nextMask();
		assertTrue(mask.contains("skills.ATTACK"));
		assertTrue(mask.contains("account"));

		session.statChanged(Skill.ATTACK);
		session.tick();
		scheduler.advance(5_000);
		assertEquals("no change, no push", 2, transport.pushes.size());
	}

	@Test
	public void aContainerChangeIsRead()
	{
		loggedIn();
		game.container(InventoryID.INV, new Game.Slot(0, 995, 10));
		session.containerChanged(InventoryID.INV);
		assertEquals(Arrays.asList("inventory"), nextMask());

		session.containerChanged(InventoryID.INV);
		scheduler.advance(5_000);
		assertEquals(2, transport.pushes.size());
	}

	@Test
	public void varChangesAreReadOnTheNextTick()
	{
		loggedIn();
		game.varbit(VarbitID.STARTED_ARDOUGNE_DIARY, 1)
			.varbit(VarbitID.IRONMAN, 1)
			.varp(VarPlayerID.SLAYER_COUNT, 10)
			.varp(VarPlayerID.COLLECTION_COUNT, 5);
		session.varChanged(-1, VarbitID.STARTED_ARDOUGNE_DIARY);
		session.varChanged(-1, VarbitID.IRONMAN);
		session.varChanged(VarPlayerID.SLAYER_COUNT, -1);
		session.varChanged(VarPlayerID.COLLECTION_COUNT, -1);
		session.varChanged(VarPlayerID.CA_TASK_COMPLETED_0, -1);
		session.varChanged(12345, -1);
		session.tick();

		List<String> mask = nextMask();
		assertTrue(mask.toString(), mask.containsAll(Arrays.asList("account", "diaries.ARDOUGNE", "slayer",
			"collection_log.unique_obtained")));
	}

	@Test
	public void aKillCountVarpIsReadOnTheNextTick()
	{
		int table = net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.ID;
		game.dbRow(table, 1, net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.COL_BOSSNAME, "Vorkath",
			net.runelite.api.gameval.DBTableID.HiscoresBossesInfo.COL_BOSSVARP, 4000);
		loggedIn();
		game.varp(4000, 7);
		session.varChanged(4000, -1);
		session.tick();
		assertEquals(Arrays.asList("boss_kc.VORKATH"), nextMask());
	}

	@Test
	public void varsAreIgnoredWhileNotTracked()
	{
		session.varChanged(VarPlayerID.QP, VarbitID.IRONMAN);
		loggedIn();
		session.tick();
		scheduler.advance(5_000);
		assertEquals(1, transport.pushes.size());
	}

	@Test
	public void questsAreReadWhenQuestPointsMoveAndOnASweep()
	{
		loggedIn();
		int reads = game.questReads;
		for (int i = 0; i < GameSession.QUEST_SWEEP_TICKS - 1; i++)
		{
			session.tick();
		}
		assertEquals("no quest reads between sweeps", reads, game.questReads);
		session.tick();
		assertTrue(game.questReads > reads);

		reads = game.questReads;
		session.varChanged(VarPlayerID.QP, -1);
		session.tick();
		assertTrue(game.questReads > reads);
	}

	@Test
	public void combatAchievementsGoOutOnLoginAndOnEachCompletion()
	{
		game.enumOf(3981, 900, 901).struct(900, 1306, 0).struct(900, 1308, "Noxious Foe")
			.struct(901, 1306, 1).struct(901, 1308, "Barrows Novice");
		session.loggedIn();
		session.tick();
		scheduler.runDue();
		assertTrue(transport.last().getUpdateMask().getPathsList().contains("combat_achievements.tasks.0"));
		transport.ok();

		game.varp(VarPlayerID.CA_TASK_COMPLETED_0, 0b10);
		session.varChanged(VarPlayerID.CA_TASK_COMPLETED_0, -1);
		session.tick();
		List<String> mask = nextMask();
		assertTrue(mask.toString(), mask.contains("combat_achievements.tasks.1"));
		assertFalse(mask.toString(), mask.contains("combat_achievements.tasks.0"));
	}

	@Test
	public void aDrawnCollectionLogPageIsRecorded()
	{
		loggedIn();
		game.viewedPage = new Game.CollectionLogPage("Vorkath",
			Arrays.asList(new Game.CollectionLogEntry(22006, true, 1)));
		session.collectionLogDrawn();
		List<String> mask = nextMask();
		assertTrue(mask.contains("collection_log.items.22006"));

		session.collectionLogDrawn();
		scheduler.advance(5_000);
		assertNull(transport.waiting);
	}

	@Test
	public void switchingAGroupOffClearsIt()
	{
		loggedIn();
		off.add(StateStore.SKILLS);
		session.groupsChanged();
		assertTrue(nextMask().contains("skills.ATTACK"));

		session.groupsChanged();
		scheduler.advance(5_000);
		assertNull(transport.waiting);
	}
}
