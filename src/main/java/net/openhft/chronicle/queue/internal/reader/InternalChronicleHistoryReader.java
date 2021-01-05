package net.openhft.chronicle.queue.internal.reader;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.core.util.Histogram;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.reader.HistoryReader;
import net.openhft.chronicle.queue.util.ToolsUtil;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;

public final class InternalChronicleHistoryReader implements HistoryReader {

    private static final int SUMMARY_OUTPUT_UNSET = -999;

    static {
        ToolsUtil.warnIfResourceTracing();
    }

    private final Supplier<? extends ChronicleQueue> queueSupplier;

    private Path basePath;
    private Consumer<String> messageSink;
    private boolean progress = false;
    private TimeUnit timeUnit = TimeUnit.NANOSECONDS;
    private boolean histosByMethod = false;
    private Map<String, Histogram> histos = new LinkedHashMap<>();
    private long ignore = 0;
    private long counter = 0;
    private long measurementWindowNanos = 0;
    private long firstTimeStampNanos = 0;
    private long lastWindowCount = 0;
    private int summaryOutputOffset = SUMMARY_OUTPUT_UNSET;
    private int lastHistosSize = 0;

    public InternalChronicleHistoryReader() {
        this.queueSupplier = null;
    }

    public InternalChronicleHistoryReader(@NotNull final Supplier<? extends ChronicleQueue> queueSupplier) {
        this.queueSupplier = requireNonNull(queueSupplier);
    }

    public InternalChronicleHistoryReader withMessageSink(final Consumer<String> messageSink) {
        this.messageSink = messageSink;
        return this;
    }

    public InternalChronicleHistoryReader withBasePath(final Path path) {
        this.basePath = path;
        return this;
    }

    public InternalChronicleHistoryReader withProgress(boolean p) {
        this.progress = p;
        return this;
    }

    public InternalChronicleHistoryReader withTimeUnit(TimeUnit p) {
        this.timeUnit = p;
        return this;
    }

    public InternalChronicleHistoryReader withHistosByMethod(boolean b) {
        this.histosByMethod = b;
        return this;
    }

    public InternalChronicleHistoryReader withIgnore(long ignore) {
        this.ignore = ignore;
        return this;
    }

    public InternalChronicleHistoryReader withMeasurementWindow(long measurementWindow) {
        this.measurementWindowNanos = timeUnit.toNanos(measurementWindow);
        return this;
    }

    public InternalChronicleHistoryReader withSummaryOutput(int offset) {
        this.summaryOutputOffset = offset;
        return this;
    }

    @NotNull
    private ChronicleQueue createQueue() {
        if (queueSupplier != null)
            return queueSupplier.get();

        if (!Files.exists(basePath))
            throw new IllegalArgumentException(String.format("Path %s does not exist", basePath));

        return SingleChronicleQueueBuilder
                .binary(basePath.toFile())
                .readOnly(true)
                .build();
    }

    public void execute() {
        readChronicle();
        if (measurementWindowNanos == 0)
            outputData();
    }

    public Map<String, Histogram> readChronicle() {
        try (final ChronicleQueue q = createQueue()) {
            final ExcerptTailer tailer = q.createTailer();
            final WireParselet parselet = parselet();
            final FieldNumberParselet fieldNumberParselet = (methodId, wire) -> parselet.accept(Long.toString(methodId), wire.read());
            MessageHistory.set(new VanillaMessageHistory());
            try (final MethodReader mr = new VanillaMethodReader(tailer, true, parselet, fieldNumberParselet, null, parselet)) {

                while (!Thread.currentThread().isInterrupted() && mr.readOne()) {
                    ++counter;
                    if (this.progress && counter % 1_000_000L == 0) {
                        System.out.println("Progress: " + counter);
                    }
                }
            }
        }

        return histos;
    }

    public void outputData() {
        if (summaryOutputOffset != SUMMARY_OUTPUT_UNSET)
            printSummary();
        else
            printPercentilesSummary();
    }

    private void printPercentilesSummary() {
        // we should also consider the case where >1 output messages are from 1 incoming

        if (histos.size() == 0) {
            messageSink.accept("No data");
            return;
        }
        int counter = 0;
        messageSink.accept("Timings below in " + timeUnit.name());
        final StringBuilder sb = new StringBuilder("sourceId        ");
        histos.forEach((id, histogram) -> sb.append(String.format("%12s ", id)));
        messageSink.accept(sb.toString());
        messageSink.accept("count:  " + count());
        messageSink.accept("50:     " + percentiles(counter++));
        messageSink.accept("90:     " + percentiles(counter++));
        messageSink.accept("99:     " + percentiles(counter++));
        messageSink.accept("99.9:   " + percentiles(counter++));
        messageSink.accept("99.99:  " + percentiles(counter++));
        messageSink.accept("99.999: " + percentiles(counter++));
        messageSink.accept("99.9999:" + percentiles(counter++));
        messageSink.accept("worst:  " + percentiles(-1));
    }

    private void printSummary() {
        if (histos.size() > lastHistosSize) {
            messageSink.accept("relative_ts," + String.join(",", histos.keySet()));
            lastHistosSize = histos.size();
        }
        long tsSinceStart = (lastWindowCount * measurementWindowNanos) - firstTimeStampNanos;
        messageSink.accept(
                timeUnit.convert(tsSinceStart, TimeUnit.NANOSECONDS) + "," +
                        histos.values().stream().
                                map(h -> Long.toString(timeUnit.convert((long) offset(h.getPercentiles(), summaryOutputOffset), TimeUnit.NANOSECONDS))).
                                collect(Collectors.joining(",")));
    }

    private double offset(double[] percentiles, int offset) {
        return offset >= 0 ? percentiles[offset] : percentiles[percentiles.length + offset];
    }

    private String count() {
        final StringBuilder sb = new StringBuilder("        ");
        histos.forEach((id, histogram) -> sb.append(String.format("%12d ", histogram.totalCount())));
        return sb.toString();
    }

    private String percentiles(final int index) {
        final StringBuilder sb = new StringBuilder("        ");
        histos.forEach((id, histogram) -> {
            double[] percentiles = histogram.getPercentiles();
            if (index >= percentiles.length - 1) {
                sb.append(String.format("%12s ", " "));
                return;
            }
            int myIndex = index;
            if (myIndex == -1) myIndex = percentiles.length - 1;
            double value = percentiles[myIndex];
            sb.append(String.format("%12d ", timeUnit.convert((long) value, TimeUnit.NANOSECONDS)));
        });
        return sb.toString();
    }

    private WireParselet parselet() {
        return (methodName, v) -> {
            v.skipValue();
            if (counter < ignore)
                return;
            final MessageHistory history = MessageHistory.get();
            if (history == null)
                return;

            processMessage(methodName, history);

            if (history.timings() > 0) {
                long firstTiming = history.timing(0);
                if (measurementWindowNanos > 0) {
                    long windowCount = firstTiming / measurementWindowNanos;
                    if (windowCount > lastWindowCount) {
                        windowPassed();
                        lastWindowCount = windowCount;
                    }
                    if (firstTimeStampNanos == 0)
                        firstTimeStampNanos = firstTiming;
                }
            }
        };
    }

    private void processMessage(CharSequence methodName, MessageHistory history) {
        CharSequence extraHistoId = histosByMethod ? ("_" + methodName) : "";
        long lastTime = 0;
        // if the tailer has recordHistory(true) then the MessageHistory will be
        // written with a single timing and nothing else. This is then carried through
        int firstWriteOffset = history.timings() - (history.sources() * 2);
        if (!(firstWriteOffset == 0 || firstWriteOffset == 1))
            // don't know how this can happen, but there is at least one CQ that exhibits it
            return;
        for (int sourceIndex = 0; sourceIndex < history.sources(); sourceIndex++) {
            String histoId = Integer.toString(history.sourceId(sourceIndex)) + extraHistoId;
            Histogram histo = histos.computeIfAbsent(histoId, s -> histogram());
            long receivedByThisComponent = history.timing((2 * sourceIndex) + firstWriteOffset);
            long processedByThisComponent = history.timing((2 * sourceIndex) + firstWriteOffset + 1);
            histo.sample((double) (processedByThisComponent - receivedByThisComponent));
            if (lastTime == 0 && firstWriteOffset > 0) {
                Histogram histo1 = histos.computeIfAbsent("startTo" + histoId, s -> histogram());
                histo1.sample((double) (receivedByThisComponent - history.timing(0)));
            } else if (lastTime != 0) {
                Histogram histo1 = histos.computeIfAbsent(history.sourceId(sourceIndex - 1) + "to" + histoId, s -> histogram());
                // here we are comparing System.nanoTime across processes. YMMV
                histo1.sample((double) (receivedByThisComponent - lastTime));
            }
            lastTime = processedByThisComponent;
        }
        if (history.sources() > 1) {
            Histogram histoE2E = histos.computeIfAbsent("endToEnd", s -> histogram());
            histoE2E.sample((double) (history.timing(history.timings() - 1) - history.timing(0)));
        }
    }

    protected void windowPassed() {
        outputData();
        histos.values().forEach(Histogram::reset);
    }

    @NotNull
    protected Histogram histogram() {
        return new Histogram(60, 4);
    }
}
