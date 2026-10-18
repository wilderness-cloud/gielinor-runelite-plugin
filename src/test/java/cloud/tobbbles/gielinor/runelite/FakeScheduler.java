package cloud.tobbbles.gielinor.runelite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A Scheduler and clock that only move when the test says so. */
public final class FakeScheduler implements Scheduler
{
	private long now = 1_760_000_000_000L;
	private long order;
	private final List<Entry> entries = new ArrayList<>();

	public long now()
	{
		return now;
	}

	@Override
	public Task schedule(Runnable task, long delayMs)
	{
		Entry entry = new Entry(now + Math.max(0, delayMs), order++, task);
		entries.add(entry);
		return () -> entries.remove(entry);
	}

	/** Moves the clock on, running each task that falls due, in order. */
	public void advance(long ms)
	{
		long until = now + ms;
		while (true)
		{
			Entry next = entries.stream()
				.filter(e -> e.at <= until)
				.min(Comparator.comparingLong((Entry e) -> e.at).thenComparingLong(e -> e.order))
				.orElse(null);
			if (next == null)
			{
				break;
			}
			entries.remove(next);
			now = Math.max(now, next.at);
			next.task.run();
		}
		now = until;
	}

	/** Runs whatever is due now (zero-delay tasks). */
	public void runDue()
	{
		advance(0);
	}

	/** When the next task is due, relative to now; -1 when nothing is scheduled. */
	public long nextDelay()
	{
		return entries.stream().mapToLong(e -> e.at - now).min().orElse(-1);
	}

	public int scheduled()
	{
		return entries.size();
	}

	private static final class Entry
	{
		final long at;
		final long order;
		final Runnable task;

		Entry(long at, long order, Runnable task)
		{
			this.at = at;
			this.order = order;
			this.task = task;
		}
	}
}
