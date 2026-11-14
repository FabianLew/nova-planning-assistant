package pl.futureirl.nova.tools;

import java.util.List;
import java.util.Map;

/** Function list sent to the model when the session opens. Descriptions are in Polish like the conversation. */
public final class ToolDefinitions {
    private ToolDefinitions() {}

    private static Map<String, Object> fn(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false));
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static final Map<String, Object> DATE = str("Data YYYY-MM-DD w strefie twórcy. Pomiń dla dzisiaj.");
    private static final Map<String, Object> TIME = Map.of("type", "string", "description", "Godzina HH:mm", "pattern", "^([01]\\d|2[0-3]):[0-5]\\d$");

    public static final List<Map<String, Object>> ALL = List.of(
            fn("get_current_context",
                    "Zwraca aktualny czas, dzisiejszy plan z wersją i zadaniami, zajętość i oczekujące karty zgody. Wywołaj przed planowaniem.",
                    Map.of(), List.of()),
            fn("get_calendar_events", "Zwraca zajętość i zaplanowane zadania na dany dzień.",
                    Map.of("date", DATE), List.of()),
            fn("create_task",
                    "Dodaje lokalne zadanie, ale tylko gdy twórca wprost o to poprosi. Nie używaj do własnych propozycji.",
                    Map.of("title", str("Krótka nazwa zadania"), "date", DATE, "startTime", TIME,
                            "durationMin", integer("Czas trwania w minutach, domyślnie 30")),
                    List.of("title")),
            fn("propose_daily_plan",
                    "Zapisuje szkic planu dnia i pokazuje twórcy kartę do zatwierdzenia. Plan obowiązuje dopiero po jego zgodzie.",
                    Map.of("date", DATE, "items", Map.of("type", "array", "minItems", 1, "maxItems", 12, "items", Map.of(
                            "type", "object", "additionalProperties", false,
                            "required", List.of("title", "startTime", "durationMin", "reason"),
                            "properties", Map.of("title", str("Nazwa"), "startTime", TIME,
                                    "durationMin", integer("Minuty"), "costPln", Map.of("type", "number", "description", "Koszt w zł, 0 jeśli darmowe"),
                                    "reason", str("Jedno krótkie uzasadnienie"))))),
                    List.of("items")),
            fn("update_daily_plan",
                    "Proponuje przeniesienie zadania na inną godzinę. Zwraca kartę zgody z opisem konfliktu; zmiana następuje dopiero po zatwierdzeniu przez twórcę.",
                    Map.of("planId", str("Id planu z get_current_context"), "expectedVersion", integer("Wersja planu, którą znasz"),
                            "taskId", str("Id zadania"), "startTime", TIME),
                    List.of("planId", "expectedVersion", "taskId", "startTime")),
            fn("set_task_status", "Oznacza zadanie jako wykonane, pominięte lub odrzucone, gdy twórca to powie.",
                    Map.of("taskId", str("Id zadania"), "status", Map.of("type", "string", "enum", List.of("done", "skipped", "refused")),
                            "reason", str("Powód odmowy, jeśli padł")),
                    List.of("taskId", "status")),
            fn("add_busy_block", "Zapisuje zajęty czas podany przez twórcę (np. montaż 9–17:30).",
                    Map.of("date", DATE, "startTime", TIME, "endTime", TIME, "label", str("Czym jest zajęty")),
                    List.of("startTime", "endTime", "label")),
            fn("save_memory",
                    "Zapamiętuje fakt lub preferencję. explicit=true tylko gdy twórca sam wprost to stwierdził (np. 'zapamiętaj, że…'). "
                    + "Twoje wnioski zapisuj z explicit=false, trafią do potwierdzenia. Sarkazmu i żartów nie zapisuj. "
                    + "Dla informacji wrażliwych (zdrowie, finanse, inne osoby) ustaw sensitive=true.",
                    Map.of("content", str("Jedno zdanie w trzeciej osobie lub cytat twórcy"),
                            "explicit", Map.of("type", "boolean"), "sensitive", Map.of("type", "boolean"),
                            "importance", integer("1-5, domyślnie 3")),
                    List.of("content", "explicit", "sensitive")),
            fn("get_experiment_status",
                    "Zwraca dzień eksperymentu, reguły, budżet (dostępne, wydane, rezerwy), odmowy i wczorajszą ocenę.",
                    Map.of(), List.of()),
            fn("propose_activity",
                    "Proponuje jedną aktywność (płatną lub nie). Karta zatwierdza plan i rezerwę budżetu. Zakupu dokonuje twórca, ty niczego nie kupujesz ani nie rezerwujesz.",
                    Map.of("date", DATE, "title", str("Nazwa"), "startTime", TIME, "durationMin", integer("Minuty"),
                            "costPln", Map.of("type", "number", "description", "Szacowany koszt w zł, 0 jeśli darmowe"),
                            "reason", str("Krótkie uzasadnienie")),
                    List.of("title", "startTime", "durationMin", "costPln", "reason")),
            fn("record_expense",
                    "Księguje rzeczywisty wydatek, który twórca podał (kwota w zł). Jeśli dotyczy zaplanowanej aktywności, podaj taskId.",
                    Map.of("amountPln", Map.of("type", "number"), "description", str("Na co"), "taskId", str("Id zadania, jeśli dotyczy")),
                    List.of("amountPln", "description")),
            fn("search_memory", "Szuka potwierdzonych wspomnień pasujących do zapytania (maks. 5).",
                    Map.of("query", str("Słowa kluczowe")), List.of("query")));
}
