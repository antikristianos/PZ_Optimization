#!/usr/bin/env python3
"""Re-encode videos that sit in cold storage (gs://diegov-videos-coldline) in place, 2026-10-12: the gpu-screen-recorder
captures above ~60 Mbps (storms, rain: up to 150 Mbps at 5K) and the old SDR H.264 / HEVC recordings, with the house
AV1 10-bit PQ encode (harness/encode-av1-hdr.sh, NVENC CQ 22; SDR mapped to PQ). Run it as a queue media job.

    harness/cold-reencode.py <manifest.jsonl> [--max-minutes M]

manifest lines: {"name": <object>} (objects under the bucket). Per object: download, encode, check (AV1, PQ, same
duration), and replace it only when the result is at most 85 % of the original, with the generation it was read at
(never over a newer object); the bucket's 7-day soft delete keeps the replaced version. Results go to
~/.cache/pzopt-cold-store/reencode-done.jsonl (a rerun skips those); --max-minutes stops starting new files after M minutes
so a batch stays a short queue job. Afterwards `cold-store.py index <run>...` re-lists the run pages' sizes.
"""
import argparse, json, os, shutil, subprocess, sys, time
from pathlib import Path

BUCKET = "diegov-videos-coldline"
GCLOUD = os.environ.get("GCLOUD", str(Path.home() / "Downloads/google-cloud-sdk/bin/gcloud"))
STATE = Path.home() / ".cache/pzopt-cold-store"
ENCODE = Path(__file__).resolve().parent / "encode-av1-hdr.sh"
KEEP = 0.85
# a conditional (--if-generation-match) upload of a big file failed as a parallel composite upload (2026-10-12)
ENV = dict(os.environ, CLOUDSDK_STORAGE_PARALLEL_COMPOSITE_UPLOAD_ENABLED="False")


def err(r):
    """stderr without gcloud's progress dots"""
    return " ".join(l for l in r.stderr.splitlines() if l.strip(". ") and "throughput" not in l)[-400:]


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                        "stream=codec_name,color_transfer:format=duration", "-of", "json", str(path)], capture_output=True, text=True)
    j = json.loads(r.stdout or "{}")
    s = (j.get("streams") or [{}])[0]
    return s.get("codec_name"), s.get("color_transfer"), float((j.get("format") or {}).get("duration") or 0)


def describe(name):
    r = subprocess.run([GCLOUD, "storage", "objects", "describe", f"gs://{BUCKET}/{name}", "--format=json"], capture_output=True, text=True)
    return json.loads(r.stdout) if r.returncode == 0 else None


def one(name, tmp):
    meta = describe(name)
    if meta is None:
        return {"name": name, "status": "gone"}
    gen, old = str(meta["generation"]), int(meta["size"])
    src, out = tmp / ("src" + Path(name).suffix), tmp / "out.mp4"
    for f in (src, out):
        f.unlink(missing_ok=True)
    r = subprocess.run([GCLOUD, "storage", "cp", "-q", f"gs://{BUCKET}/{name}#{gen}", str(src)], capture_output=True, text=True, env=ENV)
    if r.returncode != 0:
        return {"name": name, "status": "download failed", "err": err(r)}
    c0, t0, d0 = probe(src)
    r = subprocess.run([str(ENCODE), str(src), str(out)], env=dict(os.environ, NOJPG="1"), capture_output=True, text=True)
    if r.returncode != 0:
        return {"name": name, "status": "encode failed", "err": r.stderr.strip()[-300:]}
    c1, t1, d1 = probe(out)
    new = out.stat().st_size
    if c1 != "av1" or t1 != "smpte2084" or abs(d1 - d0) > max(0.5, 0.01 * d0):
        return {"name": name, "status": "check failed", "probe": [c0, t0, d0, c1, t1, d1]}
    if new > old * KEEP:
        return {"name": name, "status": "kept original", "old": old, "new": new}
    cmd = [GCLOUD, "storage", "cp", "-q", f"--if-generation-match={gen}", "--content-type=video/mp4",
           f"--custom-metadata=pzopt-reencoded=av1_nvenc-cq22,pzopt-original-bytes={old},pzopt-original-codec={c0}"]
    if meta.get("cache_control"):
        cmd.append(f"--cache-control={meta['cache_control']}")
    r = subprocess.run(cmd + [str(out), f"gs://{BUCKET}/{name}"], capture_output=True, text=True, env=ENV)
    if r.returncode != 0:
        return {"name": name, "status": "upload failed", "old": old, "new": new, "err": err(r)}
    return {"name": name, "status": "replaced", "old": old, "new": new, "codec": c0, "seconds": round(d0, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--max-minutes", type=float, default=0)
    args = ap.parse_args()
    STATE.mkdir(parents=True, exist_ok=True)
    donef = STATE / "reencode-done.jsonl"
    done = {json.loads(l)["name"] for l in open(donef) if l.strip()} if donef.exists() else set()
    todo = [json.loads(l)["name"] for l in open(args.manifest) if l.strip()]
    todo = [n for n in todo if n not in done]
    tmp = STATE / "reencode-tmp"
    tmp.mkdir(exist_ok=True)
    t0, saved = time.time(), 0
    print(f"{len(todo)} videos to do", flush=True)
    for i, name in enumerate(todo, 1):
        if args.max_minutes and time.time() - t0 > args.max_minutes * 60:
            print(f"stopping after {args.max_minutes} min: {len(todo) - i + 1} left for the next batch", flush=True)
            break
        res = one(name, tmp)
        saved += res.get("old", 0) - res.get("new", 0) if res["status"] == "replaced" else 0
        with open(donef, "a") as f:
            f.write(json.dumps(res) + "\n")
        print(f"{time.strftime('%H:%M:%S')} {i}/{len(todo)} {res['status']} {res.get('old', 0) / 1e6:.0f} -> "
              f"{res.get('new', 0) / 1e6:.0f} MB  saved {saved / 1e9:.2f} GB  {name.split('/harness/')[-1]}", flush=True)
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"done: {saved / 1e9:.2f} GB saved in {(time.time() - t0) / 60:.0f} min", flush=True)


if __name__ == "__main__":
    sys.exit(main())
