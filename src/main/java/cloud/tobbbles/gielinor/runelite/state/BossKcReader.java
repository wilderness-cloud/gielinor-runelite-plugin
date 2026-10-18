package cloud.tobbbles.gielinor.runelite.state;

import cloud.tobbbles.gielinor.runelite.game.Game;
import cloud.tobbbles.gielinor.runelite.v1.BossRecord;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.DBTableID;

/**
 * Boss kill counts from the client's own hiscores-bosses table: each row names a boss and the varp
 * holding its count. Exact, and current whenever the player is logged in. Personal bests aren't in
 * a var, so they stay unset.
 */
final class BossKcReader
{
	/** The varps the last read found, to tell a kill count change from any other var change. */
	private final Set<Integer> varps = new HashSet<>();

	/** Every boss in the table, keyed in upper snake case. Records carry no Observed time yet. */
	Map<String, BossRecord> read(Game game)
	{
		Map<String, BossRecord> bosses = new LinkedHashMap<>();
		varps.clear();
		for (int row : game.dbRows(DBTableID.HiscoresBossesInfo.ID))
		{
			Object name = game.dbField(row, DBTableID.HiscoresBossesInfo.COL_BOSSNAME);
			Object varp = game.dbField(row, DBTableID.HiscoresBossesInfo.COL_BOSSVARP);
			String key = name instanceof String ? Keys.upperSnake((String) name) : null;
			if (key == null || !(varp instanceof Integer))
			{
				continue;
			}
			int varpId = (Integer) varp;
			varps.add(varpId);
			bosses.put(key, BossRecord.newBuilder()
				.setName((String) name)
				.setKills(Math.max(0, game.varp(varpId)))
				.build());
		}
		return bosses;
	}

	boolean isKillCountVarp(int varpId)
	{
		return varps.contains(varpId);
	}
}
