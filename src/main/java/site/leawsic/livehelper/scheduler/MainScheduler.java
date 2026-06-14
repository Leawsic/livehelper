package site.leawsic.livehelper.scheduler;

import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class MainScheduler {
    public interface ExecutableTask {
        void run(boolean isOutOfMemoryRecovery, long startNs);
    }

    private record Task(long nano, ExecutableTask task) implements Comparable<Task> {
        @Override
        public int compareTo(Task other) {
            return Long.compare(this.nano, other.nano);
        }
    }

    private static final PriorityQueue<Task> QUEUE = new PriorityQueue<>(16);
    private static final double OVERSHOOT_SMOOTHING = 0.1;
    private static final long MAX_CURRENT_OVERSHOOT_NS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long MAX_AVERAGE_OVERSHOOT_NS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long SPIN_SAFETY_BUFFER_NS = 500_000L;

    private static long averageOvershootNs = 0L;

    private MainScheduler() {}

    public static void submitTask(long nano, ExecutableTask task) {
        QUEUE.add(new Task(Math.max(System.nanoTime(), nano), task));
    }

    public static void tick(boolean isOutOfMemoryRecovery) {
        Task task = QUEUE.peek();
        if (task == null) {
            return;
        }

        if (task.nano - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(10)) {
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(5));
            return;
        }

        QUEUE.remove(task);
        sleepUntil(task.nano);
        task.task.run(isOutOfMemoryRecovery, task.nano);
    }

    private static void sleepUntil(long targetTimeNs) {
        long remainingTimeNs;
        while ((remainingTimeNs = targetTimeNs - System.nanoTime()) > 0L) {
            if (remainingTimeNs > averageOvershootNs + SPIN_SAFETY_BUFFER_NS) {
                long sleepStartTimeNs = System.nanoTime();
                long expectedSleepTimeNs = remainingTimeNs - averageOvershootNs - SPIN_SAFETY_BUFFER_NS;
                if (!Thread.interrupted()) {
                    LockSupport.parkNanos(expectedSleepTimeNs);
                    long currentOvershootNs = System.nanoTime() - sleepStartTimeNs - expectedSleepTimeNs;
                    if (currentOvershootNs > 0L && currentOvershootNs < MAX_CURRENT_OVERSHOOT_NS) {
                        averageOvershootNs = Math.min(
                            (long) (OVERSHOOT_SMOOTHING * currentOvershootNs + (1 - OVERSHOOT_SMOOTHING) * averageOvershootNs),
                            MAX_AVERAGE_OVERSHOOT_NS
                        );
                    }
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
