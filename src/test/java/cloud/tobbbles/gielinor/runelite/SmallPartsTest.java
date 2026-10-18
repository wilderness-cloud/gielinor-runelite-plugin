package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Servers, Groups, the config defaults, ConfigTokenStore and ExecutorScheduler. */
public class SmallPartsTest
{
	@Test
	public void serversAreNormalised()
	{
		assertEquals(GielinorConfig.DEFAULT_SERVER, Servers.normalise(null));
		assertEquals(GielinorConfig.DEFAULT_SERVER, Servers.normalise("  "));
		assertEquals(GielinorConfig.DEFAULT_SERVER, Servers.normalise("///"));
		assertEquals("http://localhost:4000", Servers.normalise(" http://localhost:4000// "));
	}

	@Test
	public void theApprovalPageHasTheCodeWhenOffered()
	{
		assertNull(Servers.approvalUrl(null));
		AccountClient.DeviceCode code = new AccountClient.DeviceCode();
		code.verification_uri = "https://g/device";
		assertEquals("https://g/device", Servers.approvalUrl(code));
		code.verification_uri_complete = "https://g/device?code=ABCD";
		assertEquals("https://g/device?code=ABCD", Servers.approvalUrl(code));
	}

	@Test
	public void everythingButSendingIsOnByDefault()
	{
		GielinorConfig config = new GielinorConfig()
		{
		};
		assertFalse("sending is opt-in", config.sendEnabled());
		assertEquals(GielinorConfig.DEFAULT_SERVER, config.server());
		for (String group : Arrays.asList(StateStore.ACCOUNT, StateStore.SKILLS, StateStore.QUESTS, StateStore.DIARIES,
			StateStore.COMBAT_ACHIEVEMENTS, StateStore.COLLECTION_LOG, StateStore.BOSS_KC, StateStore.SLAYER,
			StateStore.INVENTORY, StateStore.EQUIPMENT, StateStore.BANK))
		{
			assertTrue(group, Groups.enabled(config, group));
		}
	}

	@Test
	public void eachGroupHasItsOwnSwitch()
	{
		Set<String> off = new HashSet<>();
		GielinorConfig config = new GielinorConfig()
		{
			@Override
			public boolean sendSkills()
			{
				return !off.contains(StateStore.SKILLS);
			}

			@Override
			public boolean sendQuests()
			{
				return !off.contains(StateStore.QUESTS);
			}

			@Override
			public boolean sendDiaries()
			{
				return !off.contains(StateStore.DIARIES);
			}

			@Override
			public boolean sendCombatAchievements()
			{
				return !off.contains(StateStore.COMBAT_ACHIEVEMENTS);
			}

			@Override
			public boolean sendCollectionLog()
			{
				return !off.contains(StateStore.COLLECTION_LOG);
			}

			@Override
			public boolean sendBossKc()
			{
				return !off.contains(StateStore.BOSS_KC);
			}

			@Override
			public boolean sendSlayer()
			{
				return !off.contains(StateStore.SLAYER);
			}

			@Override
			public boolean sendInventory()
			{
				return !off.contains(StateStore.INVENTORY);
			}

			@Override
			public boolean sendEquipment()
			{
				return !off.contains(StateStore.EQUIPMENT);
			}

			@Override
			public boolean sendBank()
			{
				return !off.contains(StateStore.BANK);
			}
		};
		for (String group : Arrays.asList(StateStore.SKILLS, StateStore.QUESTS, StateStore.DIARIES,
			StateStore.COMBAT_ACHIEVEMENTS, StateStore.COLLECTION_LOG, StateStore.BOSS_KC, StateStore.SLAYER,
			StateStore.INVENTORY, StateStore.EQUIPMENT, StateStore.BANK))
		{
			off.clear();
			off.add(group);
			assertFalse(group, Groups.enabled(config, group));
			assertTrue("the account has no switch", Groups.enabled(config, StateStore.ACCOUNT));
		}
	}

	private static final class MapConfig implements ConfigStore
	{
		final Map<String, String> values = new HashMap<>();

		@Override
		public String get(String key)
		{
			return values.get(key);
		}

		@Override
		public void set(String key, String value)
		{
			values.put(key, value);
		}

		@Override
		public void unset(String key)
		{
			values.remove(key);
		}
	}

	@Test
	public void theRefreshTokenLivesInConfig()
	{
		MapConfig config = new MapConfig();
		ConfigTokenStore tokens = new ConfigTokenStore(config);
		assertNull(tokens.refreshToken());

		tokens.save("https://issuer", "refresh-1");
		assertEquals("refresh-1", tokens.refreshToken());
		assertEquals("https://issuer", tokens.issuer());

		tokens.clear();
		assertNull(tokens.refreshToken());
		assertNull(tokens.issuer());
	}

	@Test
	public void theClientIdIsMadeOnceAndAnInvalidOneReplaced()
	{
		MapConfig config = new MapConfig();
		String id = ConfigTokenStore.clientId(config);
		assertTrue(id.matches("[A-Za-z0-9_-]{8,64}"));
		assertEquals(id, ConfigTokenStore.clientId(config));

		config.set(ConfigTokenStore.CLIENT_ID, "bad id!");
		String replaced = ConfigTokenStore.clientId(config);
		assertNotEquals("bad id!", replaced);
		assertEquals(replaced, config.get(ConfigTokenStore.CLIENT_ID));
	}

	@Test
	public void theExecutorSchedulerRunsAndCancelsAndSurvivesAThrowingTask() throws Exception
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		try
		{
			ExecutorScheduler scheduler = new ExecutorScheduler(executor);
			scheduler.schedule(() ->
			{
				throw new IllegalStateException("boom");
			}, -5);

			CompletableFuture<String> ran = new CompletableFuture<>();
			scheduler.schedule(() -> ran.complete("ran"), 0);
			assertEquals("ran", ran.get(5, TimeUnit.SECONDS));

			CompletableFuture<String> cancelled = new CompletableFuture<>();
			scheduler.schedule(() -> cancelled.complete("ran"), 60_000).cancel();
			assertTrue(executor.getQueue().isEmpty() || executor.getQueue().stream().allMatch(r ->
				((java.util.concurrent.Future<?>) r).isCancelled()));
			assertFalse(cancelled.isDone());
		}
		finally
		{
			executor.shutdownNow();
		}
	}
}
