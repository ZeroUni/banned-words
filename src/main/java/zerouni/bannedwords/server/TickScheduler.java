package zerouni.bannedwords.server;

import zerouni.bannedwords.BannedWords;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A custom tick-based scheduler for delayed task execution.
 * Runs tasks after a specified number of server ticks have elapsed.
 * Since Minecraft runs at 20 TPS, 1 tick = 50ms.
 */
public class TickScheduler {
    private static final int TICKS_PER_SECOND = 20;
    private final ConcurrentLinkedQueue<ScheduledTask> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicLong currentTick = new AtomicLong(0);

    /**
     * Represents a task scheduled to run at a specific tick.
     */
    private static class ScheduledTask {
        final long targetTick;
        final Runnable task;

        ScheduledTask(long targetTick, Runnable task) {
            this.targetTick = targetTick;
            this.task = task;
        }
    }

    /**
     * Schedules a task to run after the specified delay in milliseconds.
     * @param task The task to execute
     * @param delayMillis The delay in milliseconds
     */
    public void schedule(Runnable task, long delayMillis) {
        if (task == null) {
            BannedWords.LOGGER.warn("Attempted to schedule null task");
            return;
        }

        // Convert milliseconds to ticks (1 tick = 50ms at 20 TPS)
        long delayTicks = Math.max(1, delayMillis / (1000 / TICKS_PER_SECOND));
        long targetTick = currentTick.get() + delayTicks;
        
        tasks.offer(new ScheduledTask(targetTick, task));
        BannedWords.LOGGER.debug("Scheduled task to run in {} ticks ({}ms delay)", delayTicks, delayMillis);
    }

    /**
     * Called every server tick to update the scheduler and execute pending tasks.
     * This method should be called from the main server thread.
     */
    public void tick() {
        long tick = currentTick.incrementAndGet();
        
        // Execute all tasks that are ready
        ScheduledTask task;
        while ((task = tasks.peek()) != null && task.targetTick <= tick) {
            tasks.poll(); // Remove from queue
            try {
                task.task.run();
                BannedWords.LOGGER.debug("Executed scheduled task at tick {}", tick);
            } catch (Exception e) {
                BannedWords.LOGGER.error("Error executing scheduled task: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Gets the current tick count.
     * @return The current tick count
     */
    public long getCurrentTick() {
        return currentTick.get();
    }

    /**
     * Gets the number of pending tasks.
     * @return The number of tasks waiting to be executed
     */
    public int getPendingTaskCount() {
        return tasks.size();
    }

    /**
     * Clears all pending tasks.
     */
    public void clear() {
        int clearedCount = tasks.size();
        tasks.clear();
        if (clearedCount > 0) {
            BannedWords.LOGGER.info("Cleared {} pending scheduled tasks", clearedCount);
        }
    }
}
