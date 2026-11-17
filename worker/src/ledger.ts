// Pure AC-hours logic, mirroring ACUsageLedger in the iOS and Android apps.

export const AC_STATE = "ac_out";
/** Gaps longer than this aren't counted as an element run (matches the apps). */
export const MAX_GAP_SECS = 3600;
const SAST_OFFSET_MS = 2 * 3600 * 1000; // Africa/Johannesburg: UTC+2, no DST

export interface Sample {
  t: number; // epoch ms
  state: string | null;
}

export interface HistoryResponse {
  timestamps?: string[];
  state?: (string | null)[];
}

/** "YYYY-MM-DD" of the SAST calendar day containing `ms`. */
export function sastDay(ms: number): string {
  return new Date(ms + SAST_OFFSET_MS).toISOString().slice(0, 10);
}

/** "YYYY-MM" of the SAST month containing `ms`, shifted by `deltaMonths`. */
export function sastMonth(ms: number, deltaMonths = 0): string {
  const d = new Date(ms + SAST_OFFSET_MS);
  const m = d.getUTCFullYear() * 12 + d.getUTCMonth() + deltaMonths;
  return `${Math.floor(m / 12)}-${String((m % 12) + 1).padStart(2, "0")}`;
}

/** Server arrays are newest-first and parallel; returns valid samples oldest-first. */
export function toSamples(h: HistoryResponse): Sample[] {
  const ts = h.timestamps ?? [];
  const out: Sample[] = [];
  for (let i = 0; i < ts.length; i++) {
    const t = Date.parse(ts[i]);
    if (!Number.isNaN(t)) out.push({ t, state: h.state?.[i] ?? null });
  }
  return out.sort((a, b) => a.t - b.t);
}

/** Seconds the element was on per SAST day within one fetched window. */
export function acSecondsByDay(samples: Sample[]): Map<string, number> {
  const days = new Map<string, number>();
  for (let i = 0; i < samples.length - 1; i++) {
    if (samples[i].state !== AC_STATE) continue;
    const dt = Math.min((samples[i + 1].t - samples[i].t) / 1000, MAX_GAP_SECS);
    if (dt <= 0) continue;
    const day = sastDay(samples[i].t);
    days.set(day, (days.get(day) ?? 0) + dt);
  }
  return days;
}

/**
 * The window's oldest day is usually only partly covered, so a day keeps the larger
 * of its stored and freshly computed totals rather than being overwritten.
 */
export function mergeDay(stored: number | undefined, fresh: number): number {
  return Math.max(stored ?? 0, fresh);
}
