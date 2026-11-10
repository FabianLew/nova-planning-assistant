# NOVA · Future IRL

Polski asystent głosowy planujący dzień (pilot do vloga Future IRL). Specyfikacja: `Future-IRL_specyfikacja-MVP_PL.pdf`.

## Architektura

- `web/` – Next.js PWA (eksport statyczny). Ekran Rozmowa łączy mikrofon przez WebRTC bezpośrednio z OpenAI Realtime.
- `server/` – Spring Boot 3.5 / Java 21. Logowanie, krótkotrwały klucz sesji, zapis transkrypcji, osi czasu i pomiarów. Stały klucz OpenAI jest tylko tutaj.
- PostgreSQL 16, migracje Flyway.

W produkcji jeden obraz Dockera: Spring Boot serwuje zbudowany klient z tego samego originu.

## Uruchomienie lokalne

```bash
docker compose up -d                       # Postgres na :5432 (nova/nova)
cd server && OPENAI_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.profiles=dev   # :8080, hasło: nova-dev
cd web && npm install && npm run dev       # :3000, /api proxy do :8080
```

Mikrofon w Safari na iPhonie działa tylko po HTTPS, więc testy na telefonie robimy na wdrożeniu (lub przez tunel HTTPS).

## Testy

```bash
cd server && mvn test     # wymaga Postgresa z bazą nova_test (TEST_DATABASE_URL)
cd web && npm test && npm run lint
```

## Zmienne środowiskowe

Patrz `.env.example`. Wymagane: `OPENAI_API_KEY`, `NOVA_PASSWORD` (min. 8 znaków), `DATABASE_URL` (format JDBC), `DATABASE_USER`, `DATABASE_PASSWORD`.

## API sesji głosowej (M1)

| Metoda | Ścieżka | Opis |
|---|---|---|
| POST | `/api/sessions` | Otwiera sesję: zwraca `clientSecret` (ważny 2 min), `rtcUrl`, głos, VAD, limit czasu |
| POST | `/api/sessions/{id}/connected` | Klient zestawił WebRTC |
| POST | `/api/sessions/{id}/messages` | Finalna transkrypcja (idempotentna po `providerItemId`, flaga `interrupted`) |
| POST | `/api/sessions/{id}/events` | Oś czasu: `latency.reply`, `latency.interrupt`, `connection.lost`, `mic.muted`… |
| GET | `/api/sessions/{id}/metrics` | p50/p95 względem celów M1 (≤ 2 s, ≤ 400 ms) |
| POST | `/api/sessions/{id}/end` | Koniec sesji |

## Test M1 na iPhonie

1. Otwórz aplikację w Safari, „Udostępnij → Do ekranu początkowego”.
2. Rozmowa → Rozpocznij → 20 tur po polsku, potem 20 razy wejdź NOVIE w słowo.
3. „Pomiary M1” pokazuje p95 na żywo; pełne dane pod `/api/sessions/{id}/metrics`.
4. Powtórz na głośniku, w słuchawkach i na sieci komórkowej (warunek rozpoczęcia pilota, str. 9).
