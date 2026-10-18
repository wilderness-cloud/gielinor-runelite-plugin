package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.state.StateStore;

/** Each group of data to the setting that switches it. */
final class Groups
{
	private Groups()
	{
	}

	static boolean enabled(GielinorConfig config, String group)
	{
		switch (group)
		{
			case StateStore.SKILLS:
				return config.sendSkills();
			case StateStore.QUESTS:
				return config.sendQuests();
			case StateStore.DIARIES:
				return config.sendDiaries();
			case StateStore.COMBAT_ACHIEVEMENTS:
				return config.sendCombatAchievements();
			case StateStore.COLLECTION_LOG:
				return config.sendCollectionLog();
			case StateStore.BOSS_KC:
				return config.sendBossKc();
			case StateStore.SLAYER:
				return config.sendSlayer();
			case StateStore.INVENTORY:
				return config.sendInventory();
			case StateStore.EQUIPMENT:
				return config.sendEquipment();
			case StateStore.BANK:
				return config.sendBank();
			default:
				// The account (type, totals) has no switch: it is what linking is for.
				return true;
		}
	}
}
