package cloud.tobbbles.gielinor.runelite;

import cloud.tobbbles.gielinor.runelite.account.AccountClient;
import cloud.tobbbles.gielinor.runelite.account.Link;
import cloud.tobbbles.gielinor.runelite.panel.GielinorPanel;
import cloud.tobbbles.gielinor.runelite.relay.Pusher;
import cloud.tobbbles.gielinor.runelite.relay.RelayClient;
import cloud.tobbbles.gielinor.runelite.state.Collectors;
import cloud.tobbbles.gielinor.runelite.state.Convert;
import cloud.tobbbles.gielinor.runelite.state.StateStore;
import cloud.tobbbles.gielinor.runelite.v1.CharacterRef;
import cloud.tobbbles.gielinor.runelite.v1.PluginBuild;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
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
 */
@Slf4j
@PluginDescriptor(
	name = "Gielinor",
	description = "Sends your character's state to Gielinor, for the Gielinor app",
	tags = {"goals", "tracker", "sync", "planner", "progress"}
)
public class GielinorPlugin extends Plugin
{
	static final String VERSION = "0.1.0";
	/** Quests change rarely and cost a script each to read: sweep about every five minutes. */
	private static final int QUEST_SWEEP_TICKS = 500;

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

	private Link link;
	private Pusher pusher;
	private Collectors collectors;
	private GielinorPanel panel;
	private NavigationButton navigation;
	private Link.State lastLinkState;

	// Client thread only.
	private boolean loginPending;
	private boolean tracked;
	private boolean accountDirty;
	private boolean questsDirty;
	private boolean diariesDirty;
	private int ticksSinceQuests;

	@Override
	protected void startUp()
	{
		Scheduler scheduler = new ExecutorScheduler(executor);
		String server = server();
		AccountClient account = new AccountClient(okHttpClient, gson, server);
		link = new Link(account, new ConfigTokenStore(configManager), scheduler, System::currentTimeMillis);
		lastLinkState = link.state();

		StateStore store = new StateStore();
		RelayClient relay = new RelayClient(okHttpClient, gson, server + "/runelite");
		pusher = new Pusher(store, relay::push, link, scheduler, System::currentTimeMillis, clientId(),
			PluginBuild.newBuilder().setVersion(VERSION).build());
		collectors = new Collectors(client, itemManager, store, this::groupEnabled, System::currentTimeMillis);

		panel = new GielinorPanel(new PanelActions());
		BufferedImage icon = ImageUtil.loadImageResource(GielinorPlugin.class, "icon.png");
		navigation = NavigationButton.builder()
			.tooltip("Gielinor")
			.icon(icon)
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

	// ---- game events (client thread) ----

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// The account hash, profile and name settle over the next tick.
			loginPending = true;
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			loginPending = false;
			tracked = false;
			pusher.loggedOut();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!sending())
		{
			return;
		}
		if (loginPending)
		{
			login();
			return;
		}
		if (!tracked)
		{
			return;
		}

		boolean changed = false;
		if (accountDirty)
		{
			accountDirty = false;
			changed |= collectors.readAccount();
		}
		if (diariesDirty)
		{
			diariesDirty = false;
			changed |= collectors.readDiaries();
		}
		if (questsDirty || ++ticksSinceQuests >= QUEST_SWEEP_TICKS)
		{
			questsDirty = false;
			ticksSinceQuests = 0;
			changed |= collectors.readQuests();
		}
		if (changed)
		{
			pusher.changed();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (tracked && sending() && collectors.readSkill(event.getSkill()))
		{
			accountDirty = true;
			pusher.changed();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (tracked && sending() && collectors.readContainer(event.getContainerId()))
		{
			pusher.changed();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!tracked)
		{
			return;
		}
		if (event.getVarpId() == VarPlayerID.QP)
		{
			// A quest finished: its state and the account's quest points moved.
			questsDirty = true;
			accountDirty = true;
		}
		if (event.getVarbitId() == VarbitID.IRONMAN)
		{
			accountDirty = true;
		}
		if (Collectors.isDiaryVarbit(event.getVarbitId()))
		{
			diariesDirty = true;
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
			// A group switched on or off: clear what's off, read what's back on.
			clientThread.invoke(() ->
			{
				if (tracked && sending() && collectors.readAll())
				{
					pusher.changed();
				}
			});
		}
	}

	/** First tick after logging in: who is this, and is it tracked? Then read everything. */
	private void login()
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getName() == null)
		{
			return;
		}
		loginPending = false;
		RuneScapeProfileType profile = RuneScapeProfileType.getCurrent(client);
		tracked = Convert.tracked(profile, client.getWorldType());
		if (!tracked)
		{
			pusher.loggedOut();
			return;
		}
		pusher.loggedIn(CharacterRef.newBuilder()
			.setAccountHash(client.getAccountHash())
			.setRsn(me.getName())
			.setWorld(client.getWorld())
			.setProfile(profile.name())
			.setLoggedIn(true)
			.build());
		collectors.reset();
		collectors.readAll();
		ticksSinceQuests = 0;
		pusher.changed();
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
		if (on)
		{
			// Picks up a player already logged in (sending switched on, or the plugin started mid-game).
			clientThread.invoke(() ->
			{
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					loginPending = true;
				}
			});
		}
		else
		{
			clientThread.invoke(() -> tracked = false);
		}
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
			configManager.setConfiguration(GielinorConfig.GROUP, GielinorConfig.SEND_ENABLED, true);
		}
		applySending();
		refreshPanel();
	}

	private void openApprovalPage()
	{
		AccountClient.DeviceCode code = link.code();
		if (code == null)
		{
			return;
		}
		String url = code.verification_uri_complete != null ? code.verification_uri_complete : code.verification_uri;
		if (url != null)
		{
			LinkBrowser.browse(url);
		}
	}

	private void refreshPanel()
	{
		GielinorPanel shown = panel;
		if (shown == null)
		{
			return;
		}
		GielinorPanel.View view = new GielinorPanel.View();
		switch (link.state())
		{
			case LINKING:
				view.mode = GielinorPanel.View.Mode.LINKING;
				AccountClient.DeviceCode code = link.code();
				view.userCode = code != null ? code.user_code : null;
				break;
			case LINKED:
				view.mode = GielinorPanel.View.Mode.LINKED;
				break;
			case EXPIRED:
				view.mode = GielinorPanel.View.Mode.EXPIRED;
				break;
			default:
				view.mode = GielinorPanel.View.Mode.UNLINKED;
				break;
		}
		view.sending = sending();
		CharacterRef character = pusher.character();
		view.character = character != null && character.getLoggedIn() ? character.getRsn() : null;
		view.lastAckAt = pusher.lastAckAt();
		view.problem = link.problem() != null ? link.problem() : (view.sending ? pusher.problem() : null);
		shown.update(view);
	}

	private boolean groupEnabled(String group)
	{
		switch (group)
		{
			case StateStore.SKILLS:
				return config.sendSkills();
			case StateStore.QUESTS:
				return config.sendQuests();
			case StateStore.DIARIES:
				return config.sendDiaries();
			case StateStore.INVENTORY:
				return config.sendInventory();
			case StateStore.EQUIPMENT:
				return config.sendEquipment();
			case StateStore.BANK:
				return config.sendBank();
			default:
				return true;
		}
	}

	private String server()
	{
		String server = config.server() == null ? "" : config.server().trim();
		while (server.endsWith("/"))
		{
			server = server.substring(0, server.length() - 1);
		}
		return server.isEmpty() ? GielinorConfig.DEFAULT_SERVER : server;
	}

	/** One id per install, made on first run: the relay's client_id. */
	private String clientId()
	{
		String id = configManager.getConfiguration(GielinorConfig.GROUP, "clientId");
		if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}"))
		{
			id = UUID.randomUUID().toString();
			configManager.setConfiguration(GielinorConfig.GROUP, "clientId", id);
		}
		return id;
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
			configManager.setConfiguration(GielinorConfig.GROUP, GielinorConfig.SEND_ENABLED, false);
			link.unlink();
		}

		@Override
		public void startSending()
		{
			configManager.setConfiguration(GielinorConfig.GROUP, GielinorConfig.SEND_ENABLED, true);
		}
	}
}
