package net.shrimpworks.unreal.submitter;

import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.timgroup.statsd.StatsDClient;

public class RuntimeStats implements Closeable {

	private static final Duration STATS_UPDATE_TIME = Duration.ofSeconds(15);

	private final StatsDClient statsD;

	private final OperatingSystemMXBean os;
	private final ThreadMXBean threads;

	private final long startupTime;

	private final ScheduledFuture<?> schedule;

	public RuntimeStats(StatsDClient statsD, ScheduledExecutorService executor) {
		this.statsD = statsD;

		this.startupTime = System.currentTimeMillis();

		this.os = ManagementFactory.getOperatingSystemMXBean();
		this.threads = ManagementFactory.getThreadMXBean();

		// on a schedule, publish runtime stats
		this.schedule = executor.scheduleWithFixedDelay(this::update, STATS_UPDATE_TIME.toMillis(),
														STATS_UPDATE_TIME.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void close() {
		this.schedule.cancel(false);
	}

	public void update() {
		// runtime
		statsD.gauge("runtime.memory.total", Runtime.getRuntime().totalMemory());
		statsD.gauge("runtime.memory.free", Runtime.getRuntime().freeMemory());
		statsD.gauge("runtime.memory.max", Runtime.getRuntime().maxMemory());
		statsD.gauge("runtime.memory.used", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());

		// os
		{
			statsD.gauge("runtime.load.cores", Runtime.getRuntime().availableProcessors());
			statsD.gauge("runtime.system.load.avg", (long)(os.getSystemLoadAverage() * 100.0));
			if (os instanceof com.sun.management.OperatingSystemMXBean) {
				final com.sun.management.OperatingSystemMXBean sunOs = ((com.sun.management.OperatingSystemMXBean)os);

				long pTotal = sunOs.getTotalPhysicalMemorySize();
				long pFree = sunOs.getFreePhysicalMemorySize();
				statsD.gauge("runtime.system.memory.total", pTotal);
				statsD.gauge("runtime.system.memory.free", pFree);
				statsD.gauge("runtime.system.memory.used", pTotal - pFree);

				Double sysLoad = sunOs.getSystemCpuLoad();
				if (sysLoad >= 0.0) statsD.gauge("runtime.system.cpu.system", (long)(sysLoad * 100));

				Double processLoad = sunOs.getProcessCpuLoad();
				if (processLoad >= 0.0) statsD.gauge("runtime.system.cpu.process", (long)(processLoad * 100));
			}
		}

		// threads
		{
			statsD.gauge("runtime.threads.count", threads.getThreadCount());
			statsD.gauge("runtime.threads.daemon", threads.getDaemonThreadCount());
			statsD.gauge("runtime.threads.peak", threads.getPeakThreadCount());
		}

		// uptime
		statsD.gauge("runtime.uptime", System.currentTimeMillis() - startupTime);
	}

}
