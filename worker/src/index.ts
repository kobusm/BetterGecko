import { acSecondsByDay, mergeDay, sastMonth, toSamples } from "./ledger";
import { getHistory, signIn } from "./gecko";

export interface Env {
  DB: D1Database;
  GECKO_GSNS: string; // comma-separated serial numbers to track
  GECKO_STATIC_TOKEN: string;
  GECKO_EMAIL: string; // secret
  GECKO_PASSWORD: string; // secret
  API_KEY: string; // secret: bearer token for the read endpoints
}

const gsnList = (env: Env) =>
  env.GECKO_GSNS.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);

/** Fetch each device's ~3-day window and fold it into the per-day table. */
export async function collect(env: Env): Promise<{ gsn: string; ok: boolean; error?: string }[]> {
  const now = new Date().toISOString();
  const results: { gsn: string; ok: boolean; error?: string }[] = [];
  let token: string;
  try {
    token = await signIn(env.GECKO_EMAIL, env.GECKO_PASSWORD, env.GECKO_STATIC_TOKEN);
  } catch (e) {
    const error = String((e as Error).message ?? e);
    for (const gsn of gsnList(env)) {
      await logRun(env, gsn, now, false, null, null, error);
      results.push({ gsn, ok: false, error });
    }
    return results;
  }

  for (const gsn of gsnList(env)) {
    try {
      const samples = toSamples(await getHistory(token, gsn));
      const days = acSecondsByDay(samples);
      const stored = new Map<string, number>();
      if (days.size) {
        const keys = [...days.keys()];
        const rows = await env.DB.prepare(
          `SELECT day, seconds FROM ac_days WHERE gsn = ? AND day IN (${keys.map(() => "?").join(",")})`,
        ).bind(gsn, ...keys).all<{ day: string; seconds: number }>();
        for (const r of rows.results) stored.set(r.day, r.seconds);
      }
      const upserts = [...days].map(([day, secs]) =>
        env.DB.prepare(
          `INSERT INTO ac_days (gsn, day, seconds, updated_at) VALUES (?, ?, ?, ?)
           ON CONFLICT (gsn, day) DO UPDATE SET seconds = excluded.seconds, updated_at = excluded.updated_at`,
        ).bind(gsn, day, mergeDay(stored.get(day), secs), now),
      );
      if (upserts.length) await env.DB.batch(upserts);
      const first = samples.length ? new Date(samples[0].t).toISOString() : null;
      await logRun(env, gsn, now, true, samples.length, first, null);
      results.push({ gsn, ok: true });
    } catch (e) {
      const error = String((e as Error).message ?? e);
      await logRun(env, gsn, now, false, null, null, error);
      results.push({ gsn, ok: false, error });
    }
  }
  return results;
}

function logRun(env: Env, gsn: string, ranAt: string, ok: boolean, samples: number | null,
                firstTs: string | null, error: string | null) {
  return env.DB.prepare(
    "INSERT INTO runs (gsn, ran_at, ok, samples, first_ts, error) VALUES (?, ?, ?, ?, ?, ?)",
  ).bind(gsn, ranAt, ok ? 1 : 0, samples, firstTs, error).run();
}

async function monthUsage(env: Env, gsn: string, month: string) {
  const rows = await env.DB.prepare(
    "SELECT day, seconds FROM ac_days WHERE gsn = ? AND day LIKE ? ORDER BY day",
  ).bind(gsn, `${month}-%`).all<{ day: string; seconds: number }>();
  const days = rows.results.map((r) => ({ day: r.day, hours: round(r.seconds / 3600) }));
  const hours = round(rows.results.reduce((s, r) => s + r.seconds, 0) / 3600);
  return { month, hours, days };
}

const round = (n: number) => Math.round(n * 100) / 100;
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });

export default {
  async scheduled(_ctrl: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(collect(env));
  },

  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/health") return json({ ok: true });

    if (req.headers.get("Authorization") !== `Bearer ${env.API_KEY}`) {
      return json({ error: "unauthorized" }, 401);
    }

    // POST /run — collect now instead of waiting for the cron.
    if (url.pathname === "/run" && req.method === "POST") return json(await collect(env));

    // GET /usage?gsn=…[&month=YYYY-MM] — defaults to this month and last month (SAST).
    if (url.pathname === "/usage" && req.method === "GET") {
      const gsn = (url.searchParams.get("gsn") ?? gsnList(env)[0] ?? "").toLowerCase();
      if (!gsnList(env).includes(gsn)) return json({ error: "unknown gsn" }, 404);
      const month = url.searchParams.get("month");
      if (month && !/^\d{4}-\d{2}$/.test(month)) return json({ error: "month must be YYYY-MM" }, 400);
      const lastRun = await env.DB.prepare(
        "SELECT ran_at, ok, error FROM runs WHERE gsn = ? ORDER BY ran_at DESC LIMIT 1",
      ).bind(gsn).first();
      const now = Date.now();
      const months = month ? [month] : [sastMonth(now), sastMonth(now, -1)];
      return json({ gsn, lastRun, months: await Promise.all(months.map((m) => monthUsage(env, gsn, m))) });
    }

    return json({ error: "not found" }, 404);
  },
} satisfies ExportedHandler<Env>;
