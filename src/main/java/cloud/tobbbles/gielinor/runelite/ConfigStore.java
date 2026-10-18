package cloud.tobbbles.gielinor.runelite;

/**
 * The plugin's own keys in RuneLite's config (group {@value GielinorConfig#GROUP}). The plugin
 * backs it with ConfigManager; tests with a map.
 */
public interface ConfigStore
{
	String get(String key);

	void set(String key, String value);

	void unset(String key);
}
