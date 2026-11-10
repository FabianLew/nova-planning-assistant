package pl.futureirl.nova.session;

import java.util.List;
import java.util.function.ToDoubleFunction;
import pl.futureirl.nova.session.SessionRepository.EventRow;

/**
 * M1 acceptance numbers (spec p.9): end of speech to first audible reply (target p95 ≤ 2 s) and
 * barge-in to silence (target p95 ≤ 400 ms).
 *
 * <p>The client reports {@code latency.reply} with {@code ms} = time from the provider's speech_stopped event
 * to the first played audio, plus {@code vadSilenceMs}: speech_stopped fires only after that much silence, so
 * the creator's real end of speech was that much earlier. {@code latency.interrupt} carries {@code ms} from
 * speech_started to the assistant's audio being cleared.
 */
public record LatencyReport(Stats reply, Stats replyFromSpeechEnd, Stats interrupt) {

    public record Stats(int count, Double p50, Double p95, Double max, Double targetP95, Boolean meetsTarget) {}

    static LatencyReport from(List<EventRow> events) {
        var replies = events.stream().filter(e -> "latency.reply".equals(e.type())).toList();
        var interrupts = events.stream().filter(e -> "latency.interrupt".equals(e.type())).toList();
        return new LatencyReport(
                stats(replies, e -> num(e, "ms"), null),
                stats(replies, e -> num(e, "ms") + num(e, "vadSilenceMs"), 2000.0),
                stats(interrupts, e -> num(e, "ms"), 400.0));
    }

    private static Stats stats(List<EventRow> rows, ToDoubleFunction<EventRow> f, Double target) {
        var values = rows.stream().mapToDouble(f).filter(v -> v >= 0).sorted().toArray();
        if (values.length == 0) {
            return new Stats(0, null, null, null, target, null);
        }
        var p95 = percentile(values, 95);
        return new Stats(values.length, percentile(values, 50), p95, values[values.length - 1], target,
                target == null ? null : p95 <= target);
    }

    /** Nearest-rank percentile. */
    static double percentile(double[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static double num(EventRow e, String key) {
        var v = e.payload().get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
