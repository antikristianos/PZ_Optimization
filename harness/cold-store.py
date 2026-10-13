#!/usr/bin/env python3
"""Runs and videos to the cold-storage bucket (2026-09-27, the disk was full): gs://diegov-videos-coldline (Coldline,
public read: anyone can play a linked video), objects at the path the file has under the home directory of the main checkout
(home/diegov/Documents/ZedProjects/PZ_Optimization/<path in the checkout>; a worktree's files go to the main checkout's
path: run folder names are unique). Every run video uploaded is linked in the Grafana DBs (local and remote, table
run_videos: the Runs table's "video" column and the Run dashboard's video panel).

    harness/cold-store.py runs [--delete] [--min-age-h H] [--keep GLOB]... <runs dir>...
                                                    whole run folders (gcloud storage rsync, one per run), except the raw
                                                    dev dumps (*.f16, *.f32, *.bin, capture/frames.rgba|gray, profiler/backup_*);
                                                    text files from 4 KB on go up gzip-encoded (2026-10-12); the dumps only
                                                    fed metrics and videos that exist elsewhere; then each folder's
                                                    index.html and its run_data link; --delete removes a local folder
                                                    (raw dumps included) once the bucket holds every other file of it at
                                                    its size, except folders newer than H hours or matching a --keep glob
    harness/cold-store.py index [<run name>...]     (re)writes index.html for every run folder in the bucket (or the named
                                                    ones) and links them all in the Grafana DBs (table run_data: the Runs
                                                    table's "data" column and the Run dashboard's "Run data" panel)
    harness/cold-store.py export                    runs the Grafana DB holds whose folder is gone (removed worktrees, never
                                                    uploaded): their frames / overlay / sysmon samples from the local DB as
                                                    gzip-encoded CSV at the folder's place in the bucket, indexed and linked
    harness/cold-store.py videos [--delete] [--min-age-h H] <dir>...
                                                    the video files under the dirs (*.mp4, *.mkv, *.webm, *.mov); --delete
                                                    removes each local copy once the bucket holds it at the same size
                                                    (git-tracked files are never deleted); --min-age-h skips files newer
    harness/cold-store.py link <run> <object>       record one run's video by hand

The bucket is public (allUsers read, since 2026-09-27; personal videos moved to the private
gs://diegov-videos-coldline-private): the links play for everyone, and every read is billed (Coldline retrieval + egress). Progress and every upload go to ~/.cache/pzopt-cold-store/log.txt;
finished run folders to ~/.cache/pzopt-cold-store/done-runs.txt (a rerun skips them).
"""
import argparse
import concurrent.futures as cf
import fnmatch
import gzip
import html
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

BUCKET = "diegov-videos-coldline"
MAIN = Path.home() / "Documents/ZedProjects/PZ_Optimization"
PREFIX = "home/diegov/Documents/ZedProjects/PZ_Optimization"
GCLOUD = os.environ.get("GCLOUD", str(Path.home() / "Downloads/google-cloud-sdk/bin/gcloud"))
STATE = Path.home() / ".cache/pzopt-cold-store"
VIDEO_EXT = (".mp4", ".mkv", ".webm", ".mov")
# never uploaded: raw dev dumps, and the GameProfiler's backups of earlier sessions (run.sh copied the whole
# ~/Zomboid/Recording until 2026-10-12: the same CSVs sat in up to 13 runs)
RAW = r".*\.(f16|f32|bin)$|(^|.*/)frames\.(rgba|gray)$|(^|.*/)profiler/backup_[^/]*/.*"
# stored gzip-encoded (Content-Encoding: gzip; browsers, curl and gcloud get the plain file back), 2026-10-12
TEXT = {".csv": "text/csv", ".tsv": "text/tab-separated-values", ".json": "application/json",
        ".out": "text/plain; charset=utf-8", ".txt": "text/plain; charset=utf-8", ".log": "text/plain; charset=utf-8",
        ".jfr": "application/octet-stream"}
TEXT_RE = r".*\.(" + "|".join(e[1:] for e in TEXT) + r")$"
TEXT_MIN = 4096  # smaller text files go up plain


def is_text(f):
    return f.suffix.lower() in TEXT and f.stat().st_size >= TEXT_MIN


def gz_bytes(f):
    """f gzip-compressed, deterministic (no name, mtime 0): the same file always gives the same object size."""
    return gzip.compress(f.read_bytes(), compresslevel=9, mtime=0)


def log(msg):
    STATE.mkdir(parents=True, exist_ok=True)
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    print(line, flush=True)
    with open(STATE / "log.txt", "a") as f:
        f.write(line + "\n")


def tree_root(p):
    """The checkout a path belongs to (the directory holding harness/)."""
    p = Path(p).resolve()
    for d in [p] + list(p.parents):
        if (d / "harness").is_dir() and (d / "src").is_dir():
            return d
    raise SystemExit(f"{p}: not inside a checkout")


def object_of(path):
    path = Path(path).resolve()
    return f"{PREFIX}/{path.relative_to(tree_root(path)).as_posix()}"


def url_of(obj):
    return f"https://storage.googleapis.com/{BUCKET}/{urllib.parse.quote(obj)}"


def listing(prefix):
    """object -> size under a prefix."""
    out = subprocess.run([GCLOUD, "storage", "ls", "-l", "-r", f"gs://{BUCKET}/{prefix}/**"], capture_output=True, text=True)
    sizes = {}
    for line in out.stdout.splitlines():
        f = line.split(None, 2)
        if len(f) == 3 and f[0].isdigit() and f[2].startswith("gs://"):
            sizes[f[2][len(f"gs://{BUCKET}/"):]] = int(f[0])
    return sizes


# ---- the Grafana DBs ----

def psql(sql, remote):
    env = dict(os.environ, PGHOST="127.0.0.1", PGPORT="5433", PGUSER="pzopt", PGPASSWORD="pzopt", PGDATABASE="pzopt")
    if remote:
        cfg = {}
        for line in (Path.home() / ".config/pzopt/grafana-remote.env").read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                cfg[k.strip().removeprefix("export ").strip()] = v.strip().strip('"').strip("'")
        env.update(PGHOST=cfg.get("PZ_REMOTE_PGHOST", "127.0.0.1"), PGPORT=cfg.get("PZ_REMOTE_PGPORT", "15433"),
                   PGUSER=cfg.get("PZ_REMOTE_PGUSER", "pzopt"), PGPASSWORD=cfg.get("PZ_REMOTE_PGPASSWORD", ""),
                   PGDATABASE=cfg.get("PZ_REMOTE_PGDATABASE", "pzopt"), PGSSLMODE="disable")
    r = subprocess.run(["psql", "-X", "-q", "-1", "-v", "ON_ERROR_STOP=1", "-f", "-"], input=sql, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"  db ({'remote' if remote else 'local'}): {r.stderr.strip()[:300]}")
    return r.returncode == 0


def record(rows):
    """rows: (run, object, bytes); one per run (its recording.mp4, else its largest video)."""
    if not rows:
        return
    vals = ",".join("('%s','%s','%s',%d)" % (r.replace("'", "''"), url_of(o).replace("'", "''"), o.replace("'", "''"), b) for r, o, b in rows)
    sql = ("CREATE TABLE IF NOT EXISTS run_videos (run text PRIMARY KEY, url text, object text, bytes bigint, uploaded timestamptz DEFAULT now());"
           f"INSERT INTO run_videos (run, url, object, bytes) VALUES {vals} ON CONFLICT (run) DO UPDATE SET url = EXCLUDED.url, "
           "object = EXCLUDED.object, bytes = EXCLUDED.bytes, uploaded = now();")
    for remote in (False, True):
        psql(sql, remote)


RUN_OBJ = re.compile(rf"^({re.escape(PREFIX)}/harness/(?:archive/[^/]+/)?runs/([^/]+))/(.+)$")
DASHBOARD = "https://pzo.diegov.dev/d/pzopt-run/run?var-run="
# the frame-time sources, first in each index page and linked on their own in the Run dashboard
FRAME_FILES = [("pzopt-frames.out", "every game frame (Stats)"), ("frames.csv", "every game frame, exported from the Grafana DB"),
               ("pzopt-overlay.out", "every presented frame, GPU ms, thread loads"),
               ("overlay.csv", "every presented frame, GPU ms, thread loads, exported from the Grafana DB"),
               ("mangohud.csv", "MangoHud frame log"), ("pzopt-pacing.out", "frame pacing"), ("present.txt", "display flip times"),
               ("sysmon.csv", "CPU / GPU utilization and power")]


def run_folders(sizes):
    """bucket listing (object -> size) -> {run: (folder object, {path in the folder: size})}"""
    runs = {}
    for o, s in sizes.items():
        m = RUN_OBJ.match(o)
        if m and m.group(3) != "index.html":
            runs.setdefault(m.group(2), (m.group(1), {}))[1][m.group(3)] = s
    return runs


def size_text(b):
    for unit in ("B", "KB", "MB", "GB"):
        if b < 1000 or unit == "GB":
            return f"{b:.0f} {unit}" if unit == "B" else f"{b:.1f} {unit}"
        b /= 1000


def index_page(run, files):
    q = lambda p: html.escape(urllib.parse.quote(p))  # noqa: E731
    frames = "".join(f'<li><a href="{q(n)}">{n}</a> ({size_text(files[n])}): {d}</li>' for n, d in FRAME_FILES if n in files)
    rows = "".join(f'<tr><td><a href="{q(p)}">{html.escape(p)}</a></td><td>{size_text(s)}</td></tr>' for p, s in sorted(files.items()))
    return f"""<!doctype html><meta charset="utf-8"><title>{html.escape(run)}</title>
<style>body{{font:14px system-ui,sans-serif;margin:2em;background:#111;color:#ddd}}a{{color:#8ab8ff}}td{{padding:1px 1.5em 1px 0}}</style>
<h1>{html.escape(run)}</h1>
<p>A PZ Optimization harness run (github.com/xD3I/PZ_Optimization). Its dashboard: <a href="{DASHBOARD}{q(run)}">PZ run</a>.
The raw frame dumps of dev captures (capture/frames.gray|rgba, *.f16, *.f32, *.bin) and the profiler's copies of earlier
sessions (profiler/backup_*) are not kept. Text files are stored gzip-encoded (the sizes below); a browser or curl gets the
plain file.</p>
<h2>Frame data</h2><ul>{frames or "<li>none in this run</li>"}</ul>
<h2>All files ({len(files)}, {size_text(sum(files.values()))})</h2><table>{rows}</table>
"""


def record_data(rows):
    """rows: (run, index object, frames object or None, files, bytes)"""
    sql = ["CREATE TABLE IF NOT EXISTS run_data (run text PRIMARY KEY, url text, frames_url text, files int, bytes bigint, "
           "uploaded timestamptz DEFAULT now());"]
    esc = lambda s: "NULL" if s is None else "'" + s.replace("'", "''") + "'"  # noqa: E731
    for i in range(0, len(rows), 500):
        vals = ",".join(f"({esc(r)},{esc(url_of(o))},{esc(url_of(f) if f else None)},{n},{b})" for r, o, f, n, b in rows[i:i + 500])
        sql.append(f"INSERT INTO run_data (run, url, frames_url, files, bytes) VALUES {vals} ON CONFLICT (run) DO UPDATE SET "
                   "url = EXCLUDED.url, frames_url = EXCLUDED.frames_url, files = EXCLUDED.files, bytes = EXCLUDED.bytes, uploaded = now();")
    for remote in (False, True):
        psql("".join(sql), remote)


def do_index(only=None, have=None):
    """index.html for every run folder in the bucket (or the runs named in `only`), uploaded when it changed; all linked."""
    have = listing(f"{PREFIX}/harness") if have is None else have
    runs = run_folders(have)
    if only is not None:
        runs = {r: v for r, v in runs.items() if r in only}
    mirror, up = STATE / "index", STATE / "index-up"
    shutil.rmtree(up, ignore_errors=True)
    changed, rows = 0, []
    for run, (folder, files) in runs.items():
        page = index_page(run, files)
        obj = f"{folder}/index.html"
        old = mirror / obj
        if not (old.exists() and old.read_text() == page and have.get(obj) == len(page.encode())):
            for d in (mirror, up):
                (d / obj).parent.mkdir(parents=True, exist_ok=True)
                (d / obj).write_text(page)
            changed += 1
        frames = next((f"{folder}/{n}" for n, _ in FRAME_FILES if n in files), None)
        rows.append((run, obj, frames, len(files), sum(files.values())))
    if changed:
        r = subprocess.run([GCLOUD, "storage", "cp", "-r", "--cache-control=no-cache", "--content-type=text/html; charset=utf-8",
                            str(up / PREFIX.split("/")[0]), f"gs://{BUCKET}/"], capture_output=True, text=True)
        if r.returncode != 0:
            log(f"  index upload FAILED: {r.stderr.strip()[-300:]}")
            for run, (folder, _) in runs.items():  # forget the pages, so the next index rewrites them
                (mirror / f"{folder}/index.html").unlink(missing_ok=True)
            return have
    shutil.rmtree(up, ignore_errors=True)
    record_data(rows)
    log(f"index: {len(runs)} run folders in the bucket, {changed} index pages uploaded, {len(rows)} runs linked")
    return have


EXPORTS = [("frames.csv", "SELECT rel_s, t, ms, in_route FROM frames WHERE run = {r} ORDER BY t"),
           ("overlay.csv", "SELECT rel_s, t, fps, ms, gpu_ms, cpu_load, gpu_load, game_load, render_load FROM overlay WHERE run = {r} ORDER BY t"),
           ("sysmon.csv", "SELECT * FROM sysmon WHERE run = {r} ORDER BY t")]


def do_export():
    """Runs the Grafana DB holds whose folder is neither in the bucket nor on disk (removed worktrees): their frame,
    overlay and sysmon samples from the local DB as CSV (gzip-encoded) at the folder's place in the bucket, then indexed."""
    have = listing(f"{PREFIX}/harness")
    in_bucket = set(run_folders(have))
    env = dict(os.environ, PGHOST="127.0.0.1", PGPORT="5433", PGUSER="pzopt", PGPASSWORD="pzopt", PGDATABASE="pzopt")
    out = subprocess.run(["psql", "-X", "-At", "-F", "\t", "-c", "SELECT run, path FROM runs"], env=env, capture_output=True, text=True).stdout
    gone = [(r, p) for r, p in (l.split("\t", 1) for l in out.splitlines() if "\t" in l)
            if r not in in_bucket and not Path(p).is_dir() and "/harness/" in p]
    log(f"export: {len(gone)} runs in the Grafana DB with no folder in the bucket or on disk")
    if not gone:
        return
    stage = STATE / "export"
    shutil.rmtree(stage, ignore_errors=True)
    script = []
    for run, path in gone:
        d = stage / PREFIX / ("harness/" + path.split("/harness/", 1)[1])
        d.mkdir(parents=True, exist_ok=True)
        r = "'" + run.replace("'", "''") + "'"
        for name, q in EXPORTS:
            script.append(f"\\copy ({q.format(r=r)}) TO '{d / name}' CSV HEADER")
        (d / "README.txt").write_text(f"{run}: the run folder was deleted with its worktree before it reached cold storage; these "
                                      "are its samples as the Grafana DB holds them (harness/grafana/schema.sql: frames, overlay, sysmon).\n")
    r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-f", "-"], input="\n".join(script), env=env, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"  export FAILED: {r.stderr.strip()[-300:]}")
        return
    for f in stage.rglob("*.csv"):  # a table without rows for the run
        if f.stat().st_size < 120 and len(f.read_text().splitlines()) <= 1:
            f.unlink()
    log(f"export: {sum(f.stat().st_size for f in stage.rglob('*') if f.is_file()) / 1e9:.2f} GB of CSV, uploading")
    r = subprocess.run([GCLOUD, "storage", "cp", "-r", "--gzip-local-all", str(stage / PREFIX.split("/")[0]),
                        f"gs://{BUCKET}/"], capture_output=True, text=True)
    if r.returncode != 0:
        log(f"  export upload FAILED: {r.stderr.strip()[-300:]}")
        return
    shutil.rmtree(stage, ignore_errors=True)
    do_index({run for run, _ in gone})


def primary_videos(files):
    """run folder -> its video (recording.mp4, else the largest)."""
    by = {}
    for f in files:
        run = f.parent
        if f.name == "recording.mp4" or run not in by or (by[run].name != "recording.mp4" and f.stat().st_size > by[run].stat().st_size):
            by[run] = f
    return by


def is_run_dir(d):
    return d.parent.name in ("runs",) or "archive" in d.parts


# ---- modes ----

def stored_size(f):
    """the size f has in the bucket: text files of TEXT_MIN bytes and more are stored gzip-encoded"""
    return len(gz_bytes(f)) if is_text(f) else f.stat().st_size


def upload_text(run):
    """The run folder's text files (rsync skips them): gzip-encoded (Content-Encoding: gzip) from TEXT_MIN bytes on,
    plain below, each with its TEXT content type. True when every cp succeeded."""
    raw = re.compile(RAW)
    stage = STATE / "text-stage"
    shutil.rmtree(stage, ignore_errors=True)
    groups = {}  # (content type, gzip) -> stage dir
    for f in run.rglob("*"):
        if not f.is_file() or f.is_symlink() or f.suffix.lower() not in TEXT or raw.match(f.relative_to(run).as_posix()):
            continue
        key = (TEXT[f.suffix.lower()], is_text(f))
        d = groups.setdefault(key, stage / str(len(groups)))
        out = d / object_of(f)
        out.parent.mkdir(parents=True, exist_ok=True)
        if key[1]:
            out.write_bytes(gz_bytes(f))
        else:
            shutil.copyfile(f, out)
    ok = True
    for (ctype, gz), d in groups.items():
        cmd = [GCLOUD, "storage", "cp", "-r", f"--content-type={ctype}"] + (["--content-encoding=gzip"] if gz else [])
        r = subprocess.run(cmd + [str(d / PREFIX.split("/")[0]), f"gs://{BUCKET}/"], capture_output=True, text=True)
        if r.returncode != 0:
            log(f"  text upload FAILED {run.name}: {r.stderr.strip()[-300:]}")
            ok = False
    shutil.rmtree(stage, ignore_errors=True)
    return ok


def do_runs(dirs, delete=False, min_age_h=0.0, keep=()):
    STATE.mkdir(parents=True, exist_ok=True)
    done = set((STATE / "done-runs.txt").read_text().split()) if (STATE / "done-runs.txt").exists() else set()
    now, synced = time.time(), []
    for root in dirs:
        root = Path(root).resolve()
        runs = [root] if (root / "console.txt").exists() else sorted(p for p in root.iterdir() if p.is_dir())  # (one run folder, or a runs dir)
        log(f"runs: {root} ({len(runs)} folders, {sum(1 for r in runs if str(r) in done)} done before)")
        for i, run in enumerate(runs):
            if delete and (now - run.stat().st_mtime < min_age_h * 3600 or any(fnmatch.fnmatch(run.name, k) for k in keep)):
                continue
            synced.append(run)
            if str(run) in done and not delete:  # (--delete syncs again: a folder may have changed since)
                continue
            dst = f"gs://{BUCKET}/{object_of(run)}"
            r = subprocess.run([GCLOUD, "storage", "rsync", "-r", "-x", f"{RAW}|{TEXT_RE}", str(run), dst], capture_output=True, text=True)
            if r.returncode != 0 or not upload_text(run):
                log(f"  FAILED {run.name}: {r.stderr.strip()[-300:]}")
                continue
            vids = [f for f in run.rglob("*") if f.is_file() and f.suffix.lower() in VIDEO_EXT]
            if vids:
                v = run / "recording.mp4" if (run / "recording.mp4").is_file() else max(vids, key=lambda f: f.stat().st_size)
                record([(run.name, object_of(v), v.stat().st_size)])
            if str(run) not in done:
                with open(STATE / "done-runs.txt", "a") as f:
                    f.write(f"{run}\n")
                done.add(str(run))
            if i % 20 == 0:
                log(f"  {i + 1}/{len(runs)} {run.name}")
        log(f"runs: {root} finished")
    if not synced:
        return
    have = do_index({r.name for r in synced})
    if not delete:
        return
    raw = re.compile(RAW)
    freed = removed = 0
    for run in synced:
        files = [f for f in run.rglob("*") if f.is_file() and not f.is_symlink()]
        missing = [f for f in files if not raw.match(f.relative_to(run).as_posix()) and have.get(object_of(f)) != stored_size(f)]
        if missing:
            log(f"  kept {run.name}: {len(missing)} files not in the bucket at their size (e.g. {missing[0].relative_to(run)})")
            continue
        if subprocess.run(["git", "-C", str(run), "ls-files", "."], capture_output=True, text=True).stdout.strip():
            log(f"  kept {run.name}: git-tracked files")
            continue
        freed += sum(f.stat().st_size for f in files)
        shutil.rmtree(run)
        removed += 1
    log(f"runs: {removed} of {len(synced)} folders removed locally, {freed / 1e9:.1f} GB (apparent) freed")


def git_tracked(path):
    root = tree_root(path)
    return subprocess.run(["git", "-C", str(root), "ls-files", "--error-unmatch", str(Path(path).resolve().relative_to(root))],
                          capture_output=True).returncode == 0


def do_videos(dirs, delete, min_age_h):
    now = time.time()
    files = []
    for d in dirs:
        for f in Path(d).resolve().rglob("*"):
            if f.is_file() and f.suffix.lower() in VIDEO_EXT and now - f.stat().st_mtime >= min_age_h * 3600:
                files.append(f)
    log(f"videos: {len(files)} files, {sum(f.stat().st_size for f in files) / 1e9:.1f} GB under {', '.join(map(str, dirs))}")
    have = listing(PREFIX)

    def up(f):
        o = object_of(f)
        if have.get(o) != f.stat().st_size:
            r = subprocess.run([GCLOUD, "storage", "cp", str(f), f"gs://{BUCKET}/{o}"], capture_output=True, text=True)
            if r.returncode != 0:
                return f, o, False, r.stderr.strip()[-200:]
        return f, o, True, ""

    ok = []
    with cf.ThreadPoolExecutor(4) as ex:
        for n, (f, o, good, err) in enumerate(ex.map(up, files), 1):
            if not good:
                log(f"  FAILED {f}: {err}")
                continue
            ok.append((f, o))
            if n % 25 == 0:
                log(f"  {n}/{len(files)} uploaded")
    have = listing(PREFIX)  # the bucket's word, not the upload's exit code
    rows, freed = [], 0
    runs = primary_videos([f for f, _ in ok if is_run_dir(f.parent)])
    for f, o in ok:
        size = f.stat().st_size
        if have.get(o) != size:
            log(f"  NOT IN BUCKET at its size, kept: {f}")
            continue
        if runs.get(f.parent) == f:
            rows.append((f.parent.name, o, size))
        if delete and not git_tracked(f):
            f.unlink()
            freed += size
    record(rows)
    log(f"videos: {len(ok)} in the bucket, {len(rows)} runs linked, {freed / 1e9:.1f} GB freed")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)
    a = sub.add_parser("runs")
    a.add_argument("--delete", action="store_true")
    a.add_argument("--min-age-h", type=float, default=0.0)
    a.add_argument("--keep", action="append", default=[])
    a.add_argument("dirs", nargs="+")
    i = sub.add_parser("index")
    i.add_argument("runs", nargs="*")
    sub.add_parser("export")
    b = sub.add_parser("videos")
    b.add_argument("--delete", action="store_true")
    b.add_argument("--min-age-h", type=float, default=0.0)
    b.add_argument("dirs", nargs="+")
    c = sub.add_parser("link")
    c.add_argument("run")
    c.add_argument("object")
    args = ap.parse_args()
    if args.mode == "runs":
        do_runs(args.dirs, args.delete, args.min_age_h, args.keep)
    elif args.mode == "index":
        do_index(set(args.runs) or None)
    elif args.mode == "export":
        do_export()
    elif args.mode == "videos":
        do_videos(args.dirs, args.delete, args.min_age_h)
    else:
        record([(args.run, args.object, 0)])


if __name__ == "__main__":
    sys.exit(main())
