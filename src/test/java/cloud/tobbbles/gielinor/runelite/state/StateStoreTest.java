package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.v1.ItemContainer;
import cloud.tobbbles.gielinor.runelite.v1.Skill;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class StateStoreTest
{
	private static Skill skill(int level, int xp)
	{
		return Skill.newBuilder().setLevel(level).setXp(xp).build();
	}

	@Test
	public void anUnchangedValueStaysClean()
	{
		StateStore store = new StateStore();
		assertTrue(store.put("skills.ATTACK", skill(70, 737_627)));
		store.acknowledged(store.snapshot());

		assertFalse(store.put("skills.ATTACK", skill(70, 737_627)));
		assertFalse(store.hasChanges());
		// Clearing what was never set is no change either.
		assertFalse(store.put("bank", null));
	}

	@Test
	public void aSnapshotCarriesChangedPathsAndTheirValues()
	{
		StateStore store = new StateStore();
		store.put("skills.ATTACK", skill(70, 737_627));
		store.put("skills.SAILING", skill(1, 0));
		store.put("inventory", ItemContainer.getDefaultInstance());

		StateStore.Snapshot snapshot = store.snapshot();

		assertEquals(Arrays.asList("inventory", "skills.ATTACK", "skills.SAILING"), snapshot.mask.getPathsList());
		assertEquals(70, snapshot.state.getSkillsOrThrow("ATTACK").getLevel());
		assertTrue(snapshot.state.hasInventory());
	}

	@Test
	public void anAcknowledgementKeepsWhatChangedWhileInFlight()
	{
		StateStore store = new StateStore();
		store.put("skills.ATTACK", skill(70, 737_627));
		store.put("skills.DEFENCE", skill(60, 273_742));
		StateStore.Snapshot sent = store.snapshot();

		store.put("skills.ATTACK", skill(71, 814_445));
		store.acknowledged(sent);

		StateStore.Snapshot next = store.snapshot();
		assertEquals(Collections.singletonList("skills.ATTACK"), next.mask.getPathsList());
		assertEquals(71, next.state.getSkillsOrThrow("ATTACK").getLevel());
	}

	@Test
	public void aClearedPathGoesInTheMaskWithoutAValue()
	{
		StateStore store = new StateStore();
		store.put("bank", cloud.tobbbles.gielinor.runelite.v1.Bank.getDefaultInstance());
		store.acknowledged(store.snapshot());

		assertTrue(store.put("bank", null));
		StateStore.Snapshot snapshot = store.snapshot();

		assertEquals(Collections.singletonList("bank"), snapshot.mask.getPathsList());
		assertFalse(snapshot.state.hasBank());
	}

	@Test
	public void clearingAGroupClearsEachEntry()
	{
		StateStore store = new StateStore();
		store.put("skills.ATTACK", skill(70, 737_627));
		store.put("skills.DEFENCE", skill(60, 273_742));
		store.put("bank", cloud.tobbbles.gielinor.runelite.v1.Bank.getDefaultInstance());
		store.acknowledged(store.snapshot());

		assertTrue(store.clearGroup("skills"));

		StateStore.Snapshot snapshot = store.snapshot();
		assertEquals(Arrays.asList("skills.ATTACK", "skills.DEFENCE"), snapshot.mask.getPathsList());
		assertEquals(0, snapshot.state.getSkillsCount());
	}

	@Test
	public void missingNamesAWholeMapOrOneEntry()
	{
		StateStore store = new StateStore();
		store.put("skills.ATTACK", skill(70, 737_627));
		store.put("skills.DEFENCE", skill(60, 273_742));
		store.put("inventory", ItemContainer.getDefaultInstance());
		store.acknowledged(store.snapshot());

		store.markMissing(Collections.singletonList("skills"));

		assertEquals(Arrays.asList("skills.ATTACK", "skills.DEFENCE"), store.snapshot().mask.getPathsList());
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesAPathOutsideTheContract()
	{
		new StateStore().put("skills.attack-level", skill(1, 0));
	}

	@Test
	public void knowsTheShapesTheRelayAccepts()
	{
		for (String ok : new String[]{"account", "bank", "slayer", "skills.ATTACK", "boss_kc.K2", "quests.A_B_C",
			"combat_achievements.observed", "combat_achievements.tasks.0", "combat_achievements.tasks.2147483647",
			"collection_log.observed", "collection_log.unique_obtained", "collection_log.unique_total",
			"collection_log.items.22006", "collection_log.pages.VORKATH"})
		{
			assertTrue(ok, StateStore.knownPath(ok));
		}
		for (String bad : new String[]{"skills", "nope", "skills.attack", "skills.", "skills.A-B", "account.type",
			"combat_achievements", "combat_achievements.tasks", "combat_achievements.tasks.x",
			"combat_achievements.tasks.2147483648", "combat_achievements.tasks.12345678901",
			"combat_achievements.tasks.", "combat_achievements.pages.1", "collection_log.items.-1",
			"collection_log.pages.vorkath", "collection_log.other", "collection_log.unknown.X",
			"bank.items.1", "skills.ATTACK.level.x", "nope.x.y"})
		{
			assertFalse(bad, StateStore.knownPath(bad));
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesAPathTheRelayWouldRefuse()
	{
		new StateStore().put("skills.ATTACK.level", 1);
	}
}
