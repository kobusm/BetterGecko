#!/usr/bin/env python3
"""Minimal App Store Connect API helper for BetterGecko releases.

Credentials come from ~/.appstoreconnect/config.env (never committed):
  ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH

Usage (run with ~/.appstoreconnect/venv/bin/python):
  asc.py status  <version> <build>             show build processing + version state
  asc.py notes   <version> <release-notes.md>  create the version if needed, set "What's New"
  asc.py submit  <version> <build>             attach build and submit for review (asks first)
"""
import os
import sys
import time
from pathlib import Path

import jwt
import requests

BUNDLE_ID = "com.bettergecko.app"
API = "https://api.appstoreconnect.apple.com"


def load_config():
    cfg = {}
    for line in (Path.home() / ".appstoreconnect/config.env").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            cfg[k.strip()] = os.path.expandvars(v.strip())
    return cfg


CFG = load_config()


def token():
    now = int(time.time())
    key = Path(CFG["ASC_KEY_PATH"]).read_text()
    return jwt.encode(
        {"iss": CFG["ASC_ISSUER_ID"], "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
        key, algorithm="ES256", headers={"kid": CFG["ASC_KEY_ID"], "typ": "JWT"},
    )


def call(method, path, **kw):
    r = requests.request(method, API + path, headers={"Authorization": f"Bearer {token()}"}, **kw)
    if r.status_code >= 400:
        sys.exit(f"{method} {path} -> {r.status_code}\n{r.text}")
    return r.json() if r.content else {}


def app_id():
    data = call("GET", "/v1/apps", params={"filter[bundleId]": BUNDLE_ID})["data"]
    if not data:
        sys.exit(f"No app with bundle id {BUNDLE_ID}")
    return data[0]["id"]


def find_build(aid, version, build):
    data = call("GET", "/v1/builds", params={
        "filter[app]": aid, "filter[version]": build,
        "filter[preReleaseVersion.version]": version,
    })["data"]
    return data[0] if data else None


def find_version(aid, version):
    data = call("GET", f"/v1/apps/{aid}/appStoreVersions",
                params={"filter[versionString]": version, "filter[platform]": "IOS"})["data"]
    return data[0] if data else None


def whats_new_from(path):
    """Text between the 'What's New' heading and the end of the file."""
    lines = Path(path).read_text().splitlines()
    start = next((i + 1 for i, l in enumerate(lines) if l.strip().lower().startswith("what's new")), 0)
    return "\n".join(lines[start:]).strip()


def cmd_status(version, build):
    aid = app_id()
    b = find_build(aid, version, build)
    if b:
        a = b["attributes"]
        print(f"Build {version} ({build}): {a['processingState']}, "
              f"uploaded {a['uploadedDate']}, encryption declared: {a.get('usesNonExemptEncryption')}")
    else:
        print(f"Build {version} ({build}): not found yet (still building/uploading?)")
    v = find_version(aid, version)
    print(f"App Store version {version}: {v['attributes']['appStoreState'] if v else 'not created'}")


def cmd_notes(version, notes_path):
    aid = app_id()
    v = find_version(aid, version)
    if not v:
        v = call("POST", "/v1/appStoreVersions", json={"data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version},
            "relationships": {"app": {"data": {"type": "apps", "id": aid}}},
        }})["data"]
        print(f"Created App Store version {version}")
    text = whats_new_from(notes_path)
    locs = call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations")["data"]
    for loc in locs:
        call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}", json={"data": {
            "type": "appStoreVersionLocalizations", "id": loc["id"],
            "attributes": {"whatsNew": text},
        }})
        print(f"Set What's New for {loc['attributes']['locale']}")


def cmd_submit(version, build):
    aid = app_id()
    b = find_build(aid, version, build)
    if not b or b["attributes"]["processingState"] != "VALID":
        sys.exit("Build is not processed (VALID) yet.")
    v = find_version(aid, version)
    if not v:
        sys.exit(f"Version {version} not created; run 'notes' first.")
    if input(f"Attach build {build} to {version} and submit for App Review? [y/N] ").lower() != "y":
        sys.exit("Cancelled.")
    call("PATCH", f"/v1/appStoreVersions/{v['id']}/relationships/build",
         json={"data": {"type": "builds", "id": b["id"]}})
    sub = call("POST", "/v1/reviewSubmissions", json={"data": {
        "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
        "relationships": {"app": {"data": {"type": "apps", "id": aid}}},
    }})["data"]
    call("POST", "/v1/reviewSubmissionItems", json={"data": {
        "type": "reviewSubmissionItems",
        "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sub["id"]}},
            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": v["id"]}},
        },
    }})
    call("PATCH", f"/v1/reviewSubmissions/{sub['id']}", json={"data": {
        "type": "reviewSubmissions", "id": sub["id"], "attributes": {"submitted": True},
    }})
    print(f"Submitted {version} ({build}) for review.")


if __name__ == "__main__":
    cmds = {"status": cmd_status, "notes": cmd_notes, "submit": cmd_submit}
    if len(sys.argv) != 4 or sys.argv[1] not in cmds:
        sys.exit(__doc__)
    cmds[sys.argv[1]](sys.argv[2], sys.argv[3])
