package cloud.tobbbles.gielinor.runelite;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts a development client with this plugin loaded: `./gradlew run`. */
public class GielinorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GielinorPlugin.class);
		RuneLite.main(args);
	}
}
