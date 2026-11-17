// Tiny D1 stand-in over node:sqlite, so tests run the real SQL and migration.
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

class Stmt {
  constructor(private db: DatabaseSync, private sql: string, private args: unknown[] = []) {}
  bind(...args: unknown[]) { return new Stmt(this.db, this.sql, args); }
  async all<T>() { return { results: this.db.prepare(this.sql).all(...(this.args as never[])) as T[] }; }
  async first<T>() { return (this.db.prepare(this.sql).get(...(this.args as never[])) ?? null) as T | null; }
  async run() { this.db.prepare(this.sql).run(...(this.args as never[])); return { success: true }; }
}

export function fakeD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url).pathname, "utf8"));
  return {
    raw: db,
    prepare: (sql: string) => new Stmt(db, sql),
    batch: async (stmts: Stmt[]) => Promise.all(stmts.map((s) => s.run())),
  } as unknown as D1Database & { raw: DatabaseSync };
}
