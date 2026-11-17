import { describe, expect, it } from "vitest";
import { acSecondsByDay, mergeDay, sastDay, sastMonth, toSamples } from "../src/ledger";

const at = (iso: string, plusSecs = 0) => Date.parse(iso) + plusSecs * 1000;

describe("ledger", () => {
  // Same scenario as LedgerTest in the Android app, so all three stay in step.
  // 21:30 UTC = 23:30 SAST on the 10th; 22:30 UTC = 00:30 SAST on the 11th.
  const a = "2026-10-10T21:30:00Z";
  const samples = [
    { t: at(a), state: "ac_out" },
    { t: at(a, 1800), state: "ac_out" },              // 30 min on the 10th
    { t: at(a, 3600), state: "idle" },                // 30 min on the 11th
    { t: at(a, 3600 + 7200), state: "ac_out" },       // idle gap not counted
    { t: at(a, 3600 + 7200 + 3 * 3600), state: "idle" }, // 3 h gap capped to 1 h
  ];

  it("splits on SAST midnight and caps gaps", () => {
    const d = acSecondsByDay(samples);
    expect(d.get("2026-10-10")).toBe(1800);
    expect(d.get("2026-10-11")).toBe(1800 + 3600);
  });

  it("keeps the larger total when a partial window re-covers a day", () => {
    const partial = acSecondsByDay(samples.slice(3)).get("2026-10-11")!;
    expect(partial).toBe(3600);
    expect(mergeDay(5400, partial)).toBe(5400);
    expect(mergeDay(undefined, partial)).toBe(3600);
  });

  it("parses newest-first server arrays into oldest-first samples", () => {
    const s = toSamples({
      timestamps: ["2026-10-11T10:05:00.000+02:00", "bad", "2026-10-11T10:00:00.000+02:00"],
      state: ["idle", "x", "ac_out"],
    });
    expect(s.map((x) => x.state)).toEqual(["ac_out", "idle"]);
    expect(acSecondsByDay(s).get("2026-10-11")).toBe(300);
  });

  it("computes SAST days and months, including year rollover", () => {
    expect(sastDay(at("2026-10-31T22:30:00Z"))).toBe("2026-11-01");
    expect(sastMonth(at("2026-10-31T22:30:00Z"))).toBe("2026-11");
    expect(sastMonth(at("2026-01-15T00:00:00Z"), -1)).toBe("2025-12");
    expect(sastMonth(at("2026-12-15T00:00:00Z"), 1)).toBe("2027-01");
  });
});
