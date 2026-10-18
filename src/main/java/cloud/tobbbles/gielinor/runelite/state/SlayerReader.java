package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.Slayer;
import cloud.tobbbles.gielinor.runelite.v1.SlayerTask;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The Slayer task, points and streak, read as RuneLite's own Slayer plugin reads them: the task's
 * varps point into the SlayerTask and SlayerArea DB tables, a boss task into SlayerTaskSublist, and
 * the streak counter depends on the master (Krystilia and Mortimer keep their own). The lifetime
 * tasks-completed total has no var RuneLite reads, so it stays unset.
 */
final class SlayerReader
{
	/** "Bosses", from [proc,helper_slayer_current_assignment]. */
	static final int BOSS_TASK = 98;
	static final int KRYSTILIA = 7;
	static final int MORTIMER = 10;
	/** SLAYER_MODIFIER_ID's value for a changed task amount. */
	static final int AMOUNT_MODIFIER = 2;

	static final Set<Integer> VARPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		VarPlayerID.SLAYER_COUNT, VarPlayerID.SLAYER_TARGET, VarPlayerID.SLAYER_AREA,
		VarPlayerID.SLAYER_COUNT_ORIGINAL, VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED)));
	static final Set<Integer> VARBITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		VarbitID.SLAYER_POINTS, VarbitID.SLAYER_TASKS_COMPLETED, VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED,
		VarbitID.SLAYER_MASTER, VarbitID.SLAYER_TARGET_BOSSID, VarbitID.SLAYER_MODIFIER_ID,
		VarbitID.SLAYER_MODIFIER_VALUE, VarbitID.SLAYER_MODIFIER_NEGATIVE)));

	private SlayerReader()
	{
	}

	/** Without an Observed time; the caller stamps it. */
	static Slayer read(Game game)
	{
		Slayer.Builder slayer = Slayer.newBuilder()
			.setPoints(game.varbit(VarbitID.SLAYER_POINTS))
			.setStreak(streak(game));
		int remaining = game.varp(VarPlayerID.SLAYER_COUNT);
		if (remaining > 0)
		{
			SlayerTask.Builder task = SlayerTask.newBuilder()
				.setRemaining(remaining)
				.setAmount(initialAmount(game));
			String name = taskName(game, game.varp(VarPlayerID.SLAYER_TARGET));
			if (name != null)
			{
				task.setName(name);
			}
			String location = areaName(game, game.varp(VarPlayerID.SLAYER_AREA));
			if (location != null)
			{
				task.setLocation(location);
			}
			slayer.setTask(task);
		}
		return slayer.build();
	}

	static boolean isSlayerVar(int varpId, int varbitId)
	{
		return VARPS.contains(varpId) || VARBITS.contains(varbitId);
	}

	static int streak(Game game)
	{
		switch (game.varbit(VarbitID.SLAYER_MASTER))
		{
			case KRYSTILIA:
				return game.varbit(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
			case MORTIMER:
				return game.varp(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED);
			default:
				return game.varbit(VarbitID.SLAYER_TASKS_COMPLETED);
		}
	}

	/** The amount assigned, including a task-amount modifier when one applies. */
	static int initialAmount(Game game)
	{
		int amount = game.varp(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (game.varbit(VarbitID.SLAYER_MODIFIER_ID) == AMOUNT_MODIFIER)
		{
			int value = game.varbit(VarbitID.SLAYER_MODIFIER_VALUE);
			amount += game.varbit(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1 ? -value : value;
		}
		return amount;
	}

	static String taskName(Game game, int taskId)
	{
		Integer row;
		if (taskId == BOSS_TASK)
		{
			List<Integer> bosses = game.dbRowsByValue(DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, game.varbit(VarbitID.SLAYER_TARGET_BOSSID));
			Object task = bosses.isEmpty() ? null : game.dbField(bosses.get(0), DBTableID.SlayerTaskSublist.COL_TASK);
			row = task instanceof Integer ? (Integer) task : null;
		}
		else
		{
			List<Integer> rows = game.dbRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, taskId);
			row = rows.isEmpty() ? null : rows.get(0);
		}
		Object name = row != null ? game.dbField(row, DBTableID.SlayerTask.COL_NAME_UPPERCASE) : null;
		return name instanceof String ? (String) name : null;
	}

	static String areaName(Game game, int areaId)
	{
		if (areaId <= 0)
		{
			return null;
		}
		List<Integer> rows = game.dbRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, areaId);
		Object name = rows.isEmpty() ? null : game.dbField(rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER);
		return name instanceof String ? (String) name : null;
	}
}
