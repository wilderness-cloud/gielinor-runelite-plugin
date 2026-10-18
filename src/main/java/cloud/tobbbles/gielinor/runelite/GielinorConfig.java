package cloud.tobbbles.gielinor.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(GielinorConfig.GROUP)
public interface GielinorConfig extends Config
{
	String GROUP = "gielinor";
	String SEND_ENABLED = "sendEnabled";
	String SERVER = "server";
	String DEFAULT_SERVER = "https://gielinor.tobbbles.cloud";
	/** RuneLite's required wording for a third-party server, then what this one sends. */
	String WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by "
		+ "RuneLite developers. It sends your character's skills, quests, diaries, inventory, equipment and "
		+ "bank to Gielinor, where only you can see them.";

	@ConfigSection(
		name = "What to send",
		description = "Each kind of data can be kept to yourself. Switching one off also removes it from Gielinor.",
		position = 1
	)
	String sendSection = "send";

	@ConfigSection(
		name = "Advanced",
		description = "For development and forks of Gielinor",
		position = 2,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = SEND_ENABLED,
		name = "Send to Gielinor",
		description = "Send your character's state to your linked Gielinor account. Link from the Gielinor side panel; linking turns this on.",
		warning = WARNING,
		position = 0
	)
	default boolean sendEnabled()
	{
		return false;
	}

	@ConfigItem(keyName = "sendSkills", name = "Skills", description = "Levels and XP", section = sendSection, position = 0)
	default boolean sendSkills()
	{
		return true;
	}

	@ConfigItem(keyName = "sendQuests", name = "Quests", description = "Each quest's state", section = sendSection, position = 1)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(keyName = "sendDiaries", name = "Achievement diaries", description = "Each diary tier's progress", section = sendSection, position = 2)
	default boolean sendDiaries()
	{
		return true;
	}

	@ConfigItem(keyName = "sendInventory", name = "Inventory", description = "What you're carrying", section = sendSection, position = 3)
	default boolean sendInventory()
	{
		return true;
	}

	@ConfigItem(keyName = "sendEquipment", name = "Equipment", description = "What you're wearing", section = sendSection, position = 4)
	default boolean sendEquipment()
	{
		return true;
	}

	@ConfigItem(keyName = "sendBank", name = "Bank", description = "Your bank, as of the last time you opened it", section = sendSection, position = 5)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = SERVER,
		name = "Gielinor server",
		description = "Where your account lives. Changing it unlinks this plugin; takes effect when the plugin restarts.",
		section = advancedSection,
		position = 0
	)
	default String server()
	{
		return DEFAULT_SERVER;
	}
}
