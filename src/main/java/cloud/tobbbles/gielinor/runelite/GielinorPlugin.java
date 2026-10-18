package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.game.ClientGame;
import cloud.tobbbles.gielinor.runelite.panel.GielinorPanel;
import cloud.tobbbles.gielinor.runelite.panel.Views;
import cloud.tobbbles.gielinor.runelite.relay.Pusher;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient;
import cloud.tobbbles.gielinor.runelite.state.Collectors;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.OkHttpClient;

/**
 * Links RuneLite to a Gielinor account and pushes the player's own character state to the
 * RuneLite relay (docs/design.md). Nothing is read or sent until the player links the plugin and
 * "Send to Gielinor" is on; nothing ever connects to the client.
 *
 * <p>This class only wires RuneLite to the parts: game events go to {@link GameSession}, which
 * holds the logic and is tested without a client.
 */
@Slf4j
@PluginDescriptor(
	name = "Gielinor",
	description = "Sends your character's state to Gielinor, for the Gielinor app",
	tags = {"goals", "tracker", "sync", "planner", "progress"}
)
public class GielinorPlugin extends Plugin
{
	static final String VERSION = "0.2.0";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GielinorConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ScheduledExecutorService executor;

	private ConfigStore store;
	private Link link;
	private Pusher pusher;
	private GameSession session;
	private GielinorPanel panel;
	private NavigationButton navigation;
	private Link.State lastLinkState;

	@Override
	protected void startUp()
	{
		store = new ConfigStore()
		{
			@Override
			public String get(String key)
			{
				return configManager.getConfiguration(GielinorConfig.GROUP, key);
			}

			@Override
			public void set(String key, String value)
			{
				configManager.setConfiguration(GielinorConfig.GROUP, key, value);
			}

			@Override
			public void unset(String key)
			{
				configManager.unsetConfiguration(GielinorConfig.GROUP, key);
			}
		};

		Scheduler scheduler = new ExecutorScheduler(executor);
		String server = Servers.normalise(config.server());
		link = new Link(new AccountClient(okHttpClient, gson, server), new ConfigTokenStore(store), scheduler,
			System::currentTimeMillis);
		lastLinkState = link.state();

		StateStore state = new StateStore();
		RelayClient relay = new RelayClient(okHttpClient, gson, server + "/runelite");
		pusher = new Pusher(state, relay::push, link, scheduler, System::currentTimeMillis,
			ConfigTokenStore.clientId(store), PluginBuild.newBuilder().setVersion(VERSION).build());
		ClientGame game = new ClientGame(client, itemManager);
		Collectors collectors = new Collectors(game, state, group -> Groups.enabled(config, group),
			System::currentTimeMillis);
		session = new GameSession(game, collectors, pusher, this::sending);

		panel = new GielinorPanel(new PanelActions());
		navigation = NavigationButton.builder()
			.tooltip("Gielinor")
			.icon(ImageUtil.loadImageResource(GielinorPlugin.class, "icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigation);

		link.addListener(l -> onLinkChanged());
		pusher.setListener(p -> refreshPanel());
		applySending();
		refreshPanel();
	}

	@Override
	protected void shutDown()
	{
		pusher.shutDown();
		link.cancel();
		clientToolbar.removeNavigation(navigation);
		navigation = null;
		panel = null;
	}

	@Provides
	GielinorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GielinorConfig.class);
	}

	// ---- game events, on the client thread ----

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			session.loggedIn();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			session.leftGame();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		session.tick();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		session.statChanged(event.getSkill());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		session.containerChanged(event.getContainerId());
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		session.varChanged(event.getVarpId(), event.getVarbitId());
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
		{
			// The page's items are drawn by the time the client thread gets back to us.
			clientThread.invokeLater(session::collectionLogDrawn);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GielinorConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (GielinorConfig.SEND_ENABLED.equals(event.getKey()))
		{
			applySending();
			refreshPanel();
		}
		else if (event.getKey().startsWith("send"))
		{
			clientThread.invoke(session::groupsChanged);
		}
	}

	// ---- link and sending ----

	private boolean sending()
	{
		return link != null && link.state() == Link.State.LINKED && config.sendEnabled();
	}

	private void applySending()
	{
		boolean on = sending();
		pusher.setEnabled(on);
		clientThread.invoke(() -> session.sendingChanged(on, client.getGameState() == GameState.LOGGED_IN));
	}

	/** May run on an OkHttp or scheduler thread. */
	private void onLinkChanged()
	{
		Link.State now = link.state();
		Link.State before = lastLinkState;
		lastLinkState = now;

		if (now == Link.State.LINKING && before != Link.State.LINKING)
		{
			openApprovalPage();
		}
		if (now == Link.State.LINKED && before == Link.State.LINKING)
		{
			// The player asked to link, having read what is sent: linking turns sending on.
			store.set(GielinorConfig.SEND_ENABLED, "true");
		}
		applySending();
		refreshPanel();
	}

	private void openApprovalPage()
	{
		String url = Servers.approvalUrl(link.code());
		if (url != null)
		{
			LinkBrowser.browse(url);
		}
	}

	private void refreshPanel()
	{
		GielinorPanel shown = panel;
		if (shown != null)
		{
			shown.update(Views.of(link.state(), link.code(), link.problem(), sending(),
				pusher.character(), pusher.lastAckAt(), pusher.problem()));
		}
	}

	private final class PanelActions implements GielinorPanel.Actions
	{
		@Override
		public void link()
		{
			link.start();
		}

		@Override
		public void openApprovalPage()
		{
			GielinorPlugin.this.openApprovalPage();
		}

		@Override
		public void cancelLinking()
		{
			link.cancel();
		}

		@Override
		public void unlink()
		{
			store.set(GielinorConfig.SEND_ENABLED, "false");
			link.unlink();
		}

		@Override
		public void startSending()
		{
			store.set(GielinorConfig.SEND_ENABLED, "true");
		}
	}
}
