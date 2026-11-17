-- Seconds the AC element was on, per device per SAST calendar day.
CREATE TABLE ac_days (
  gsn        TEXT NOT NULL,
  day        TEXT NOT NULL,            -- "YYYY-MM-DD" in SAST (UTC+2)
  seconds    REAL NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (gsn, day)
);

-- One row per device per cron run, so gaps in coverage are visible.
CREATE TABLE runs (
  gsn     TEXT NOT NULL,
  ran_at  TEXT NOT NULL,
  ok      INTEGER NOT NULL,
  samples INTEGER,
  first_ts TEXT,                       -- oldest sample in the fetched window
  error   TEXT
);
CREATE INDEX runs_gsn_ran_at ON runs (gsn, ran_at);
