package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.CombatAchievementTask;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Every combat achievement task with its completion, from the game's own tables: no interface
 * needs opening. Each tier is an enum of structs (a task's id and name are params on its struct),
 * and completion is a bitfield across the CA_TASK_COMPLETED varps: bit (id % 32) of varp (id / 32).
 *
 * <p>The enum, struct param ids are the game cache's (its CS2 scripts read them); RuneLite names
 * none of them. Adapted from runelite-mcp-server-plugin (BSD-2-Clause).
 */
final class CombatAchievementsReader
{
	static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	static final String[] TIERS = {"EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER"};
	static final int TASK_ID_PARAM = 1306;
	static final int TASK_NAME_PARAM = 1308;

	static final int[] COMPLETED_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};

	/** The varps a completion moves, to tell a combat achievement from any other var change. */
	static final Set<Integer> VARPS;

	static
	{
		Set<Integer> ids = new HashSet<>();
		for (int varp : COMPLETED_VARPS)
		{
			ids.add(varp);
		}
		VARPS = Collections.unmodifiableSet(ids);
	}

	private CombatAchievementsReader()
	{
	}

	/** Every task, by its game id, in tier order. */
	static Map<Integer, CombatAchievementTask> read(Game game)
	{
		Map<Integer, CombatAchievementTask> tasks = new LinkedHashMap<>();
		for (int tier = 0; tier < TIER_ENUMS.length; tier++)
		{
			int[] structs = game.enumValues(TIER_ENUMS[tier]);
			if (structs == null)
			{
				continue;
			}
			for (int struct : structs)
			{
				String name = game.structString(struct, TASK_NAME_PARAM);
				int id = game.structInt(struct, TASK_ID_PARAM);
				if (name == null || name.isEmpty() || id < 0)
				{
					continue;
				}
				tasks.put(id, CombatAchievementTask.newBuilder()
					.setName(name)
					.setTier(TIERS[tier])
					.setCompleted(completed(game, id))
					.build());
			}
		}
		return tasks;
	}

	static boolean completed(Game game, int taskId)
	{
		int varp = taskId / 32;
		if (varp >= COMPLETED_VARPS.length)
		{
			return false;
		}
		return (game.varp(COMPLETED_VARPS[varp]) & (1 << (taskId % 32))) != 0;
	}

	static boolean isCompletionVarp(int varpId)
	{
		return VARPS.contains(varpId);
	}
}
