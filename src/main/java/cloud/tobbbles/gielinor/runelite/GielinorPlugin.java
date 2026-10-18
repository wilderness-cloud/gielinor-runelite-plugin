package cloud.tobbbles.gielinor.runelite;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Links RuneLite to a Gielinor account and pushes the player's character state to the RuneLite
 * relay. Nothing is wired yet: see docs/design.md.
 */
@Slf4j
@PluginDescriptor(
	name = "Gielinor",
	description = "Sends your character's state to Gielinor, for the Gielinor app",
	tags = {"goals", "tracker", "sync", "planner"}
)
public class GielinorPlugin extends Plugin
{
	@Override
	protected void startUp()
	{
		log.debug("Gielinor started");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Gielinor stopped");
	}
}
