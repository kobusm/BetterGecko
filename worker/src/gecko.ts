// Minimal GeyserGecko REST client: sign in, then fetch performance history.
import type { HistoryResponse } from "./ledger";

const BASE = "https://geckows.co.za";
// The gateway 403s any User-Agent containing "bettergecko"; send the official app's UA.
const UA =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";
const APP_VERSION = "17";

function headers(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    "User-Agent": UA,
    "X-App-Version": APP_VERSION,
    "X-Android-App-Version": APP_VERSION,
    "X-IOS-App-Version": APP_VERSION,
    Origin: BASE,
    Referer: `${BASE}/`,
    Authorization: `Bearer ${token}`,
  };
}

async function check(res: Response, what: string): Promise<Response> {
  if (res.ok) return res;
  let msg = `HTTP ${res.status}`;
  try {
    const j = (await res.json()) as Record<string, string>;
    msg = j.customMsg ?? j.message ?? j.error ?? msg;
  } catch {}
  throw new Error(`${what}: ${msg}`);
}

/** Returns a session token. `staticToken` is the app token the server rotates now and then. */
export async function signIn(email: string, password: string, staticToken: string): Promise<string> {
  const res = await check(
    await fetch(`${BASE}/auth/signin`, {
      method: "POST",
      headers: headers(staticToken),
      body: JSON.stringify({ email, pw: password }),
    }),
    "signin",
  );
  const { token } = (await res.json()) as { token?: string };
  if (!token) throw new Error("signin: no token in response");
  return token;
}

export async function getHistory(sessionToken: string, gsn: string): Promise<HistoryResponse> {
  const url = `${BASE}/app/getPerformanceHistory?geckoSerialNumber=${encodeURIComponent(gsn)}`;
  const res = await check(await fetch(url, { headers: headers(sessionToken) }), `history ${gsn}`);
  return (await res.json()) as HistoryResponse;
}
