package cloud.tobbbles.gielinor.runelite;

/**
 * Runs a task later. The plugin backs it with RuneLite's ScheduledExecutorService (the Hub forbids
 * Thread.sleep); tests back it with a clock they move by hand.
 */
public interface Scheduler
{
	Task schedule(Runnable task, long delayMs);

	/** A scheduled task that hasn't run can be cancelled; cancelling one that has does nothing. */
	interface Task
	{
		void cancel();
	}
}
