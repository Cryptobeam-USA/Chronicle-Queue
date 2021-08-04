package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.io.SimpleCloseable;

import java.io.File;
import java.util.function.ToIntFunction;

final class FileSystemDirectoryListing extends SimpleCloseable implements DirectoryListing {
    private final File queueDir;
    private final ToIntFunction<File> fileToCycleFunction;
    private int minCreatedCycle = Integer.MAX_VALUE;
    private int maxCreatedCycle = Integer.MIN_VALUE;
    private long lastRefreshTimeMS;

    FileSystemDirectoryListing(final File queueDir,
                               final ToIntFunction<File> fileToCycleFunction) {
        this.queueDir = queueDir;
        this.fileToCycleFunction = fileToCycleFunction;
    }

    @Override
    public void onFileCreated(final File file, final int cycle) {
        onRoll(cycle);
    }

    @Override
    public void refresh(boolean force) {
        lastRefreshTimeMS = System.currentTimeMillis();
        int minCycle = Integer.MAX_VALUE;
        int maxCycle = Integer.MIN_VALUE;
        final File[] files = queueDir.listFiles((d, n) -> n.endsWith(SingleChronicleQueue.SUFFIX));
        if (files != null) {
            for (File file : files) {
                int cycle = fileToCycleFunction.applyAsInt(file);
                minCycle = Math.min(minCycle, cycle);
                maxCycle = Math.max(maxCycle, cycle);
            }
        }
        minCreatedCycle = minCycle;
        maxCreatedCycle = maxCycle;
    }

    @Override
    public long lastRefreshTimeMS() {
        return lastRefreshTimeMS;
    }

    @Override
    public int getMinCreatedCycle() {
        return minCreatedCycle;
    }

    @Override
    public int getMaxCreatedCycle() {
        return maxCreatedCycle;
    }

    @Override
    public long modCount() {
        return -1;
    }

    @Override
    public void onRoll(int cycle) {
        minCreatedCycle = Math.min(minCreatedCycle, cycle);
        maxCreatedCycle = Math.max(maxCreatedCycle, cycle);
    }
}
