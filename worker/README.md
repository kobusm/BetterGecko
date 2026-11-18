# bettergecko-ac-hours

Cloudflare Worker that keeps a server-side tally of how long the geyser element (AC) was on,
per SAST calendar day. The GeyserGecko API only returns ~3 days of 5-minute history, so a cron
fetches it every 6 hours and folds it into D1, using the same rules as `ACUsageLedger` in the
apps: split on SAST midnight, gaps capped at 1 h, a day keeps the larger of its old and new totals.

## Endpoints (all but /health need `Authorization: Bearer <API_KEY>`)

| Method | Path | |
|---|---|---|
| GET | `/usage?gsn=…` | This month and last month: `{ months: [{ month, hours, days: [{ day, hours, seconds }] }], lastRun }` |
| GET | `/usage?gsn=…&month=YYYY-MM` | One month |
| POST | `/run` | Collect now instead of waiting for the cron |
| GET | `/health` | Liveness |

## Setup

```bash
npm install
npx wrangler d1 create bettergecko-ac-hours      # paste database_id into wrangler.toml
npm run db:migrate
npx wrangler secret put GECKO_EMAIL
npx wrangler secret put GECKO_PASSWORD
npx wrangler secret put API_KEY                  # any long random string
# set GECKO_GSNS in wrangler.toml, then:
npm run deploy
```

Not the `bettergecko` Cloudflare project — that one is the ESP32 controller dashboard.

## Notes

- Hours only count from the first run onward, plus whatever the first ~3-day window covers.
- `runs` logs every attempt; a gap of more than ~3 days between successful runs means lost data.
- If sign-in starts failing with "Invalid token", the static app token rotated: update
  `GECKO_STATIC_TOKEN` (and `APIClient.swift` / `Api.kt`).
- Signing in from the worker may end the app's session on your phone if the server allows only
  one session per account; the apps re-login silently on 401, so this should only cost a retry.
