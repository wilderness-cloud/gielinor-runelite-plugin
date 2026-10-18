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
}
