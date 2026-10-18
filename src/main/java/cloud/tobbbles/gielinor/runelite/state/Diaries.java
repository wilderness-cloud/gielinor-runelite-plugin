package cloud.tobbbles.gielinor.runelite.state;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.gameval.VarbitID;

/**
 * The achievement diaries' varbits, per region and tier, under the game's own names. Three per
 * tier: the tier is complete, how many of its tasks are done, and the reward has been claimed.
 * Karamja predates the standard diary varbits: its easy to hard tiers live on the old Tai Bwo
 * Wannai ("ATJUN") varbits, where 1 means in progress and 2 done.
 *
 * <p>Keys are the contract's: the region in upper snake case. Adapted from
 * runelite-mcp-server-plugin's ProgressProvider (BSD-2-Clause).
 */
final class Diaries
{
	static final List<Region> REGIONS = Collections.unmodifiableList(Arrays.asList(
		new Region("ARDOUGNE", "Ardougne", VarbitID.STARTED_ARDOUGNE_DIARY,
			new Tier(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1, VarbitID.ARDOUGNE_EASY_COUNT, VarbitID.ARDOUGNE_EASY_REWARD),
			new Tier(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1, VarbitID.ARDOUGNE_MED_COUNT, VarbitID.ARDOUGNE_MEDIUM_REWARD),
			new Tier(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1, VarbitID.ARDOUGNE_HARD_COUNT, VarbitID.ARDOUGNE_HARD_REWARD),
			new Tier(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1, VarbitID.ARDOUGNE_ELITE_COUNT, VarbitID.ARDOUGNE_ELITE_REWARD)),
		new Region("DESERT", "Desert", VarbitID.STARTED_DESERT_DIARY,
			new Tier(VarbitID.DESERT_DIARY_EASY_COMPLETE, 1, VarbitID.DESERT_EASY_COUNT, VarbitID.DESERT_EASY_REWARD),
			new Tier(VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, 1, VarbitID.DESERT_MED_COUNT, VarbitID.DESERT_MEDIUM_REWARD),
			new Tier(VarbitID.DESERT_DIARY_HARD_COMPLETE, 1, VarbitID.DESERT_HARD_COUNT, VarbitID.DESERT_HARD_REWARD),
			new Tier(VarbitID.DESERT_DIARY_ELITE_COMPLETE, 1, VarbitID.DESERT_ELITE_COUNT, VarbitID.DESERT_ELITE_REWARD)),
		new Region("FALADOR", "Falador", VarbitID.STARTED_FALADOR_DIARY,
			new Tier(VarbitID.FALADOR_DIARY_EASY_COMPLETE, 1, VarbitID.FALADOR_EASY_COUNT, VarbitID.FALADOR_EASY_REWARD),
			new Tier(VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, 1, VarbitID.FALADOR_MED_COUNT, VarbitID.FALADOR_MEDIUM_REWARD),
			new Tier(VarbitID.FALADOR_DIARY_HARD_COMPLETE, 1, VarbitID.FALADOR_HARD_COUNT, VarbitID.FALADOR_HARD_REWARD),
			new Tier(VarbitID.FALADOR_DIARY_ELITE_COMPLETE, 1, VarbitID.FALADOR_ELITE_COUNT, VarbitID.FALADOR_ELITE_REWARD)),
		new Region("FREMENNIK", "Fremennik", VarbitID.STARTED_FREMENNIK_DIARY,
			new Tier(VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, 1, VarbitID.FREMENNIK_EASY_COUNT, VarbitID.FREMENNIK_EASY_REWARD),
			new Tier(VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, 1, VarbitID.FREMENNIK_MED_COUNT, VarbitID.FREMENNIK_MEDIUM_REWARD),
			new Tier(VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, 1, VarbitID.FREMENNIK_HARD_COUNT, VarbitID.FREMENNIK_HARD_REWARD),
			new Tier(VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, 1, VarbitID.FREMENNIK_ELITE_COUNT, VarbitID.FREMENNIK_ELITE_REWARD)),
		new Region("KANDARIN", "Kandarin", VarbitID.STARTED_KANDARIN_DIARY,
			new Tier(VarbitID.KANDARIN_DIARY_EASY_COMPLETE, 1, VarbitID.KANDARIN_EASY_COUNT, VarbitID.KANDARIN_EASY_REWARD),
			new Tier(VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, 1, VarbitID.KANDARIN_MED_COUNT, VarbitID.KANDARIN_MEDIUM_REWARD),
			new Tier(VarbitID.KANDARIN_DIARY_HARD_COMPLETE, 1, VarbitID.KANDARIN_HARD_COUNT, VarbitID.KANDARIN_HARD_REWARD),
			new Tier(VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, 1, VarbitID.KANDARIN_ELITE_COUNT, VarbitID.KANDARIN_ELITE_REWARD)),
		new Region("KARAMJA", "Karamja", VarbitID.ATJUN_STARTED,
			new Tier(VarbitID.ATJUN_EASY_DONE, 2, VarbitID.KARAMJA_EASY_COUNT, VarbitID.ATJUN_EASY_REWARD),
			new Tier(VarbitID.ATJUN_MED_DONE, 2, VarbitID.KARAMJA_MED_COUNT, VarbitID.ATJUN_MED_REWARD),
			new Tier(VarbitID.ATJUN_HARD_DONE, 2, VarbitID.KARAMJA_HARD_COUNT, VarbitID.ATJUN_HARD_REWARD),
			new Tier(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1, VarbitID.KARAMJA_ELITE_COUNT, VarbitID.KARAMJA_ELITE_REWARD)),
		new Region("KOUREND_AND_KEBOS", "Kourend & Kebos", VarbitID.STARTED_KOUREND_DIARY,
			new Tier(VarbitID.KOUREND_DIARY_EASY_COMPLETE, 1, VarbitID.KOUREND_EASY_COUNT, VarbitID.KOUREND_EASY_REWARD),
			new Tier(VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, 1, VarbitID.KOUREND_MED_COUNT, VarbitID.KOUREND_MEDIUM_REWARD),
			new Tier(VarbitID.KOUREND_DIARY_HARD_COMPLETE, 1, VarbitID.KOUREND_HARD_COUNT, VarbitID.KOUREND_HARD_REWARD),
			new Tier(VarbitID.KOUREND_DIARY_ELITE_COMPLETE, 1, VarbitID.KOUREND_ELITE_COUNT, VarbitID.KOUREND_ELITE_REWARD)),
		new Region("LUMBRIDGE_AND_DRAYNOR", "Lumbridge & Draynor", VarbitID.STARTED_LUMBRIDGE_DIARY,
			new Tier(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, 1, VarbitID.LUMBRIDGE_EASY_COUNT, VarbitID.LUMBRIDGE_EASY_REWARD),
			new Tier(VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, 1, VarbitID.LUMBRIDGE_MED_COUNT, VarbitID.LUMBRIDGE_MEDIUM_REWARD),
			new Tier(VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, 1, VarbitID.LUMBRIDGE_HARD_COUNT, VarbitID.LUMBRIDGE_HARD_REWARD),
			new Tier(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, 1, VarbitID.LUMBRIDGE_ELITE_COUNT, VarbitID.LUMBRIDGE_ELITE_REWARD)),
		new Region("MORYTANIA", "Morytania", VarbitID.STARTED_MORYTANIA_DIARY,
			new Tier(VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, 1, VarbitID.MORYTANIA_EASY_COUNT, VarbitID.MORYTANIA_EASY_REWARD),
			new Tier(VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, 1, VarbitID.MORYTANIA_MED_COUNT, VarbitID.MORYTANIA_MEDIUM_REWARD),
			new Tier(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, 1, VarbitID.MORYTANIA_HARD_COUNT, VarbitID.MORYTANIA_HARD_REWARD),
			new Tier(VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, 1, VarbitID.MORYTANIA_ELITE_COUNT, VarbitID.MORYTANIA_ELITE_REWARD)),
		new Region("VARROCK", "Varrock", VarbitID.STARTED_VARROCK_DIARY,
			new Tier(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1, VarbitID.VARROCK_EASY_COUNT, VarbitID.VARROCK_EASY_REWARD),
			new Tier(VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, 1, VarbitID.VARROCK_MED_COUNT, VarbitID.VARROCK_MEDIUM_REWARD),
			new Tier(VarbitID.VARROCK_DIARY_HARD_COMPLETE, 1, VarbitID.VARROCK_HARD_COUNT, VarbitID.VARROCK_HARD_REWARD),
			new Tier(VarbitID.VARROCK_DIARY_ELITE_COMPLETE, 1, VarbitID.VARROCK_ELITE_COUNT, VarbitID.VARROCK_ELITE_REWARD)),
		new Region("WESTERN_PROVINCES", "Western Provinces", VarbitID.STARTED_WESTERN_DIARY,
			new Tier(VarbitID.WESTERN_DIARY_EASY_COMPLETE, 1, VarbitID.WESTERN_EASY_COUNT, VarbitID.WESTERN_EASY_REWARD),
			new Tier(VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, 1, VarbitID.WESTERN_MED_COUNT, VarbitID.WESTERN_MEDIUM_REWARD),
			new Tier(VarbitID.WESTERN_DIARY_HARD_COMPLETE, 1, VarbitID.WESTERN_HARD_COUNT, VarbitID.WESTERN_HARD_REWARD),
			new Tier(VarbitID.WESTERN_DIARY_ELITE_COMPLETE, 1, VarbitID.WESTERN_ELITE_COUNT, VarbitID.WESTERN_ELITE_REWARD)),
		new Region("WILDERNESS", "Wilderness", VarbitID.STARTED_WILDERNESS_DIARY,
			new Tier(VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, 1, VarbitID.WILDERNESS_EASY_COUNT, VarbitID.WILDERNESS_EASY_REWARD),
			new Tier(VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, 1, VarbitID.WILDERNESS_MED_COUNT, VarbitID.WILDERNESS_MEDIUM_REWARD),
			new Tier(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, 1, VarbitID.WILDERNESS_HARD_COUNT, VarbitID.WILDERNESS_HARD_REWARD),
			new Tier(VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, 1, VarbitID.WILDERNESS_ELITE_COUNT, VarbitID.WILDERNESS_ELITE_REWARD))
	));

	/** Every varbit the table reads, to tell a diary change from any other varbit. */
	static final Set<Integer> VARBITS;

	static
	{
		Set<Integer> ids = new HashSet<>();
		for (Region region : REGIONS)
		{
			ids.add(region.started);
			for (Tier tier : region.tiers)
			{
				ids.add(tier.complete);
				ids.add(tier.tasks);
				ids.add(tier.reward);
			}
		}
		VARBITS = Collections.unmodifiableSet(ids);
	}

	private Diaries()
	{
	}

	static final class Region
	{
		final String key;
		final String name;
		final int started;
		/** Easy, medium, hard, elite. */
		final List<Tier> tiers;

		Region(String key, String name, int started, Tier easy, Tier medium, Tier hard, Tier elite)
		{
			this.key = key;
			this.name = name;
			this.started = started;
			this.tiers = Collections.unmodifiableList(Arrays.asList(easy, medium, hard, elite));
		}
	}

	static final class Tier
	{
		final int complete;
		final int completeAt;
		final int tasks;
		final int reward;

		Tier(int complete, int completeAt, int tasks, int reward)
		{
			this.complete = complete;
			this.completeAt = completeAt;
			this.tasks = tasks;
			this.reward = reward;
		}
	}
}
