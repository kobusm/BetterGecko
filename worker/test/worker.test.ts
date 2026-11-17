import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker, { collect, type Env } from "../src/index";
import { fakeD1 } from "./d1";

// Five-minute samples, newest-first like the server: AC on 10:00–11:00 SAST on 2026-11-16.
function history(hours = 1) {
  const timestamps: string[] = [];
  const state: string[] = [];
  const start = Date.parse("2026-11-16T08:00:00Z");
  for (let m = hours * 60; m >= 0; m -= 5) {
    timestamps.push(new Date(start + m * 60000).toISOString());
    state.push(m < hours * 60 ? "ac_out" : "idle");
  }
  return { timestamps, state };
}

let env: Env & { DB: ReturnType<typeof fakeD1> };
let hist = history();
const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  if (url.endsWith("/auth/signin")) {
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer static");
    expect(new Headers(init?.headers).get("User-Agent")).not.toMatch(/bettergecko/i);
    return Response.json({ token: "session" });
  }
  expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer session");
  return Response.json(hist);
});

beforeEach(() => {
  env = {
    DB: fakeD1(),
    GECKO_GSNS: "ABC123, def456",
    GECKO_STATIC_TOKEN: "static",
    GECKO_EMAIL: "e",
    GECKO_PASSWORD: "p",
    API_KEY: "k",
  };
  hist = history();
  vi.stubGlobal("fetch", fetchMock);
  vi.setSystemTime(new Date("2026-11-17T08:00:00Z"));
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

const get = (path: string, key = "k") =>
  worker.fetch(new Request(`https://w${path}`, { headers: { Authorization: `Bearer ${key}` } }), env);

describe("collect", () => {
  it("stores per-day seconds for every device and logs the run", async () => {
    const res = await collect(env);
    expect(res).toEqual([{ gsn: "abc123", ok: true }, { gsn: "def456", ok: true }]);
    const rows = env.DB.raw.prepare("SELECT gsn, day, seconds FROM ac_days ORDER BY gsn").all();
    expect(rows).toEqual([
      { gsn: "abc123", day: "2026-11-16", seconds: 3600 },
      { gsn: "def456", day: "2026-11-16", seconds: 3600 },
    ]);
  });

  it("never lowers a day when a later window covers less of it", async () => {
    hist = history(2);
    await collect(env);
    hist = history(1);
    await collect(env);
    const r = env.DB.raw.prepare("SELECT seconds FROM ac_days WHERE gsn='abc123'").get();
    expect(r).toEqual({ seconds: 7200 });
  });

  it("records a failed run when sign-in fails", async () => {
    fetchMock.mockImplementationOnce(async () => Response.json({ customMsg: "Invalid token" }, { status: 403 }));
    const res = await collect(env);
    expect(res[0]).toEqual({ gsn: "abc123", ok: false, error: "signin: Invalid token" });
    expect(env.DB.raw.prepare("SELECT COUNT(*) n FROM ac_days").get()).toEqual({ n: 0 });
  });
});

describe("fetch", () => {
  it("requires the API key", async () => {
    expect((await get("/usage", "nope")).status).toBe(401);
    expect((await get("/health", "nope")).status).toBe(200);
  });

  it("returns this month and last month by default", async () => {
    await collect(env);
    env.DB.raw.prepare("INSERT INTO ac_days VALUES ('abc123','2026-10-31',5400,'x')").run();
    const body = (await (await get("/usage?gsn=ABC123")).json()) as any;
    expect(body.months).toEqual([
      { month: "2026-11", hours: 1, days: [{ day: "2026-11-16", hours: 1 }] },
      { month: "2026-10", hours: 1.5, days: [{ day: "2026-10-31", hours: 1.5 }] },
    ]);
    expect(body.lastRun.ok).toBe(1);
  });

  it("rejects unknown devices and bad months", async () => {
    expect((await get("/usage?gsn=zzz")).status).toBe(404);
    expect((await get("/usage?gsn=abc123&month=2026-1")).status).toBe(400);
  });
});
