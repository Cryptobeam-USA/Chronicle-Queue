/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesRingBufferStats;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.AbstractChronicleQueue;
import net.openhft.chronicle.queue.impl.ExcerptFactory;
import net.openhft.chronicle.queue.impl.WireStore;
import net.openhft.chronicle.queue.impl.WireStorePool;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.util.function.Consumer;

import static net.openhft.chronicle.wire.Wires.lengthOf;

public class SingleChronicleQueue extends AbstractChronicleQueue implements SingleChronicleQueueFields {

    private static final String SUFFIX = ".cq4";
    public static final int TIMEOUT = 10_000;
    public static final String MESSAGE = "Timed out waiting for the header record to be ready in ";

    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(SingleChronicleQueueStore.class, "WireStore");
    }

    @NotNull
    private final RollCycle cycle;
    @NotNull
    private final RollDateCache dateCache;
    @NotNull
    private final WireStorePool pool;
    private final long epoch;
    private final boolean isBuffered;
    private final ExcerptFactory<SingleChronicleQueue> appenderFactory;
    private final ExcerptFactory<SingleChronicleQueue> tailerFactory;
    private final File path;
    private final WireType wireType;
    private final long blockSize;
    private final RollCycle rollCycle;
    private final Consumer<BytesRingBufferStats> onRingBufferStats;
    private final EventLoop eventLoop;
    private final long bufferCapacity;

    SingleChronicleQueue(@NotNull final SingleChronicleQueueBuilder builder) {
        cycle = builder.rollCycle();
        dateCache = new RollDateCache(this.cycle);
        pool = WireStorePool.withSupplier(this::acquireStore);
        epoch = builder.epoch();
        isBuffered = builder.buffered();
        appenderFactory = builder.excertpFactory();
        tailerFactory = builder.excertpFactory();
        path = builder.path();
        wireType = builder.wireType();
        blockSize = builder.blockSize();
        rollCycle = builder.rollCycle();
        eventLoop = builder.eventLoop();
        bufferCapacity = builder.bufferCapacity();
        this.onRingBufferStats = builder.onRingBufferStats();
        storeForCycle(cycle(), this.epoch);
    }

    @Override
    public long epoch() {
        return epoch;
    }

    @NotNull
    @Override
    public RollCycle rollCycle() {
        throw new UnsupportedOperationException("todo");
    }

    /**
     * @return if we uses a ring buffer to buffer the appends, the Excerts are written to the
     * Chronicle Queue using a background thread
     */
    public boolean buffered() {
        return this.isBuffered;
    }

    @Nullable
    @Override
    public EventLoop eventLoop() {
        return this.eventLoop;
    }

    @NotNull
    @Override
    public ExcerptAppender createAppender() {
        return appenderFactory.createAppender(this);
    }

    @NotNull
    @Override
    public ExcerptTailer createTailer() throws IOException {
        return tailerFactory.createTailer(this);
    }

    @NotNull
    @Override
    protected final WireStore storeForCycle(long cycle, final long epoch) {
        return this.pool.acquire(cycle, epoch);
    }


    @Override
    public void close() throws IOException {
        // todo
    }

    @Override
    protected final void release(@NotNull WireStore store) {
        this.pool.release(store);
    }

    @Override
    protected final long cycle() {
        return this.cycle.current(epoch);
    }

    @Override
    public long firstIndex() {
        final long cycle = firstCycle();
        if (cycle == -1)
            return -1;

        @NotNull final WireStore store = acquireStore(cycle, epoch());
        return ChronicleQueue.index(store.cycle(), store.firstSequenceNumber());
    }

    private long firstCycle() {
        long firstCycle = -1;

        @NotNull final String basePath = path.getAbsolutePath();
        @Nullable final File[] files = path.listFiles();

        if (files != null && files.length > 0) {
            long firstDate = Long.MAX_VALUE;
            long date;
            String name;

            for (int i = files.length - 1; i >= 0; i--) {
                try {
                    name = files[i].getAbsolutePath();
                    if (name.endsWith(SUFFIX)) {
                        name = name.substring(basePath.length() + 1);
                        name = name.substring(0, name.indexOf('.'));

                        date = dateCache.parseCount(name);
                        if (firstDate > date) {
                            firstDate = date;
                        }
                    }
                } catch (ParseException ignored) {
                    // ignored
                }
            }

            firstCycle = firstDate;
        }


        if (firstCycle == Long.MAX_VALUE) {
            return -1;
        }

        return firstCycle;
    }


    @Override
    public long lastIndex() {
        final long lastCycle = lastCycle();
        if (lastCycle == -1)
            return -1;

        return ChronicleQueue.index(lastCycle, acquireStore(lastCycle, epoch()).sequenceNumber());
    }

    private long lastCycle() {
        @NotNull final String basePath = path.getAbsolutePath();
        @Nullable final File[] files = path.listFiles();

        if (files != null && files.length > 0) {
            long lastDate = Long.MIN_VALUE;
            long date;
            String name;

            for (int i = files.length - 1; i >= 0; i--) {
                try {
                    name = files[i].getAbsolutePath();
                    if (name.endsWith(SUFFIX)) {
                        name = name.substring(basePath.length() + 1);
                        name = name.substring(0, name.indexOf('.'));

                        date = dateCache.parseCount(name);
                        if (lastDate < date) {
                            lastDate = date;
                        }
                    }
                } catch (ParseException ignored) {
                    // ignored
                }
            }

            return lastDate;
        }

        return -1;
    }


    @Override
    public Consumer<BytesRingBufferStats> onRingBufferStats() {
        return this.onRingBufferStats;
    }

    @NotNull
    @Override
    public File path() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public long blockSize() {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    @Override
    public WireType wireType() {
        return wireType;
    }

    @Override
    public long bufferCapacity() {
        return this.bufferCapacity;
    }

    // *************************************************************************
    //
    // *************************************************************************

    private MappedBytes mappedBytes(File cycleFile)
            throws FileNotFoundException {
        long chunkSize = OS.pageAlign(blockSize);
        long overlapSize = OS.pageAlign(blockSize / 4);
        return MappedBytes.mappedBytes(cycleFile, chunkSize, overlapSize);
    }

    @NotNull
    private WireStore acquireStore(final long cycle, final long epoch) {

        @NotNull final String cycleFormat = this.dateCache.formatFor(cycle);
        @NotNull final File cycleFile = new File(path, cycleFormat + SUFFIX);
        try {
            final File parentFile = cycleFile.getParentFile();
            if (parentFile != null && !parentFile.exists())
                parentFile.mkdirs();

            final MappedBytes mappedBytes = mappedBytes(cycleFile);

            //noinspection PointlessBitwiseExpression
            if (mappedBytes.compareAndSwapInt(0, Wires.NOT_INITIALIZED, Wires.META_DATA
                    | Wires.NOT_READY | Wires.UNKNOWN_LENGTH)) {
                final SingleChronicleQueueStore wireStore = new
                        SingleChronicleQueueStore(rollCycle, wireType, mappedBytes, epoch);
                final Bytes<?> bytes = mappedBytes.bytesForWrite().writePosition(4);
                wireType.apply(bytes).getValueOut().typedMarshallable(wireStore);
                wireStore.cycle(cycle);
                wireStore.writePosition(bytes.writePosition());
                mappedBytes.writeOrderedInt(0L, Wires.META_DATA
                        | Wires.toIntU30(bytes.writePosition() - 4, "Delegate too large"));
                return wireStore;
            } else {
                long end = System.currentTimeMillis() + TIMEOUT;
                while ((mappedBytes.readVolatileInt(0) & Wires.NOT_READY) == Wires.NOT_READY) {
                    if (System.currentTimeMillis() > end)
                        throw new IllegalStateException(MESSAGE + cycleFile);
                    Jvm.pause(1);
                }

                mappedBytes.readPosition(0).writePosition(mappedBytes.capacity());
                final int len = lengthOf(mappedBytes.readVolatileInt());
                mappedBytes.readLimit(mappedBytes.readPosition() + len);
                //noinspection unchecked
                return wireType.apply(mappedBytes).getValueIn().typedMarshallable();
            }
        } catch (FileNotFoundException e) {
            throw Jvm.rethrow(e);
        }
    }

}
