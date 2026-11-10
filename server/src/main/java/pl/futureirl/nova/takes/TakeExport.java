package pl.futureirl.nova.takes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON keeps ids and times; CSV is for picking scenes in a spreadsheet or the editor (spec p.7). */
public final class TakeExport {
    private TakeExport() {}

    static final String[] COLUMNS = {"take", "take_id", "event_id", "seq", "time_utc", "offset", "offset_ms", "type", "speaker", "text"};

    public static String offset(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, milli = ms % 1000;
        return String.format("%02d:%02d.%03d", m, s, milli);
    }

    public static List<Map<String, Object>> rows(List<TakeService.TakeDetail> takes) {
        var out = new ArrayList<Map<String, Object>>();
        for (var t : takes) {
            for (var e : t.events()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("take", t.take().number());
                row.put("take_id", t.take().id().toString());
                row.put("event_id", e.id().toString());
                row.put("seq", e.seq());
                row.put("time_utc", e.occurredAt().toString());
                row.put("offset", offset(e.offsetMs()));
                row.put("offset_ms", e.offsetMs());
                row.put("type", e.type());
                row.put("speaker", e.speaker() == null ? "" : e.speaker());
                row.put("text", e.text() == null ? "" : e.text());
                out.add(row);
            }
        }
        return out;
    }

    public static String csv(List<TakeService.TakeDetail> takes) {
        var sb = new StringBuilder("﻿"); // BOM so spreadsheets read Polish characters correctly
        sb.append(String.join(",", COLUMNS)).append("\r\n");
        for (var row : rows(takes)) {
            var first = true;
            for (var col : COLUMNS) {
                if (!first) sb.append(',');
                sb.append(escape(String.valueOf(row.get(col))));
                first = false;
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String escape(String v) {
        // neutralise spreadsheet formulas, then quote
        var safe = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?") ? "'" + v : v;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
