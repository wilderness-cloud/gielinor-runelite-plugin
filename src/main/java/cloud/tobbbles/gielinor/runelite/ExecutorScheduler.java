package cloud.tobbbles.gielinor.runelite;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** A Scheduler on RuneLite's shared executor. Tasks that throw are logged, not lost silently. */
@Slf4j
public class ExecutorScheduler implements Scheduler
{
	private final ScheduledExecutorService executor;

	public ExecutorScheduler(ScheduledExecutorService executor)
	{
		this.executor = executor;
	}

	@Override
	public Task schedule(Runnable task, long delayMs)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			try
			{
				task.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Gielinor: a scheduled task failed", e);
			}
		}, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
		return () -> future.cancel(false);
	}
}
