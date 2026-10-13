---
name: cold-storage
description: Free disk space by moving finished runs and their videos to GCP cold storage (gs://diegov-videos-coldline) in compressed form, re-encode high-bitrate videos there, and keep the public dashboard's cloud DB small. Use when the home pool or a worktree fills up, after a batch of recorded runs, or when asked about the bucket's size or cost or the pzopt-db disk.
---

# Cold storage of runs (compressed, since 2026-10-12)

Runs live locally only until they are analysed; then they go to the public bucket `gs://diegov-videos-coldline`
(project `diegov`, us-central1, Autoclass) and the Grafana run pages link them. Background, history and traps:
`harness/CLAUDE.md` "Cold storage of runs and videos" and "Public dashboard". Only PZ run data ever goes into this
bucket (it is public; every read is billed).

## Setup for every command

```bash
export PATH=$HOME/Downloads/google-cloud-sdk/bin:$PATH   # gcloud is not on PATH
export TMPDIR=$HOME/.cache/pzopt-cold-store/tmp          # gcloud hits the /tmp quota otherwise
mkdir -p "$TMPDIR"
```

The Bash tool runs zsh: put loops over job ids or word lists into a `bash` script (zsh does not word-split `$var`).

## Free space: the order of steps

1. **Find the high-bitrate videos while they are still local** (storm / rain captures reach 150 Mbps at 5K; old SDR
   H.264 / HEVC recordings):
   ```bash
   harness/cold-reencode.py ~/.cache/pzopt-cold-store/reencode-$(date +%m%d).jsonl --scan harness/runs [--mbps 60]
   ```
   It writes a manifest of the videos that are not AV1 PQ or run above 60 Mbps, under the object names the upload will
   use. Most captures are 20-45 Mbps and are not listed.
2. **Upload and delete the run folders** (videos included, linked in both Grafana DBs, `index.html` per run):
   ```bash
   harness/cold-store.py runs --delete --min-age-h 2 [--keep 'GLOB'] harness/runs
   ```
   What makes it small happens on its own:
   - raw dev dumps (`*.f16`, `*.f32`, `*.bin`, `capture/frames.rgba|gray`) are never uploaded;
   - the GameProfiler's `profiler/backup_*` copies of earlier sessions are skipped (and `run.sh` no longer copies them);
   - text files of 4 KB and more (csv, tsv, json, out, txt, log, jfr) go up gzip-encoded
     (`Content-Encoding: gzip`, browsers / curl / gcloud get the plain file).

   A folder is deleted only once the bucket holds every kept file at its stored size. Git-tracked files are never
   deleted. The desktop uplink is ~6 MB/s (~6 min a GB), so run large uploads in the background.
   Other sessions keep runs in their own worktrees (`~/pzopt-wt/*`, `.claude/worktrees/*`): ask them before running it
   on their folders.
3. **Re-encode the listed videos in the bucket**, as queue media jobs (encodes never run beside a run; see `run-queue`):
   ```bash
   harness/queue.sh submit media --label reenc-<n> --intent "..." --progress "..." --size 240 \
     -- harness/cold-reencode.py <manifest> --max-minutes 0.01
   ```
   `--max-minutes 0.01` makes each job one video (~2-4 min), so it fits between other sessions' runs; a rerun skips
   what `~/.cache/pzopt-cold-store/reencode-done.jsonl` already holds. Per video it downloads the object at its
   generation, encodes with `harness/encode-av1-hdr.sh` (NVENC CQ 22, SDR mapped to PQ), checks codec / PQ / duration,
   and replaces it only when the new file is at most 85 % of the old one. ~60 Mbps captures come out at ~87 % and stay
   as they are. Downloads count against the 100 GB a month of free egress.
   **One job pending at a time** when other sessions use the queue: submit the next only when yours has started. To
   count your own jobs, compare the status file's first line with explicit `[ "$st" = pending ]` tests. A
   `case $st in $pattern)` with `pattern='pending*|blocked*'` never matches (an expanded `|` is literal); on 2026-10-13
   that queued 124 jobs, made Jev unreachable and stalled the desktop queue for 17 minutes.
4. **Re-index the runs whose video changed**, so their pages show the new sizes:
   ```bash
   harness/cold-store.py index <run name>...   # without names: every run folder in the bucket
   ```

Stand-alone videos (outside run folders): `harness/cold-store.py videos --delete [--min-age-h H] <dir>...`.

## The cloud database (public dashboard)

`pzopt-db` (e2-micro + 30 GB pd-standard in us-central1 = the always-free tier) runs Postgres 15 with TimescaleDB. The
run-sample tables are compressed hypertables; a policy compresses new data after 12 h, so nothing is needed per run.
- `harness/grafana/cloud.sh status`: remote DB size, spool, tunnel. The disk must stay ≤ 30 GB to stay free.
- After a `seed` (it runs it itself) or a new run-sample table: `harness/grafana/cloud.sh columnstore` (idempotent;
  `harness/grafana/cloud/columnstore.sql`, add the table to its list).
- Long remote operations: keep the follower out with `ALTER ROLE pzopt CONNECTION LIMIT 0` (its writes spool and replay),
  `-1` after. Never `NOLOGIN` (Timescale then refuses the compression policies).

## Cost (list prices, 2026-10-13)

The bucket held 241.8 GB after the 2026-10-12 pass (385 GB before). With Autoclass, an object is billed at Standard
$0.020/GB for 30 days, then Nearline $0.010, Coldline $0.004 after 90 days and Archive $0.0012 after a year. A read
moves it back to Standard with no retrieval fee. That is ~$4.80 in the first month, falling to ~$1 a month. Deleted or
replaced objects are billed for 7 more days (soft delete). Public downloads past 100 GB a month cost ~$0.12/GB. The
budget "pzopt videos hard cap" (EUR 10 a month) disables billing on the whole project when it is reached. The DB VM and
disk, Cloud Run and the budget function are inside their free tiers.

## Bulk work on what the bucket already holds

Re-processing many objects (gzip, PNG recompression, deletions) is best done on a temporary spot VM in us-central1:
no egress fee, no uplink limit. The global CPU quota is 32 (a c2d-highcpu-16 fits beside pzopt-db). There is no GPU
quota, and SVT-AV1 at 5K runs under 1 fps there, so videos stay on the desktop's NVENC. Delete the VM and its disk
afterwards. Large uploads conditional on a generation need `CLOUDSDK_STORAGE_PARALLEL_COMPOSITE_UPLOAD_ENABLED=False`.
