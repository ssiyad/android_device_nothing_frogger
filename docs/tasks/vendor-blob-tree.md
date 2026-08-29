# Version the extracted vendor blob tree

`vendor/nothing/frogger/` is not a git repo and not a `repo` project, yet
`frogger-vendor.mk` copies files straight out of it into the image.

Consequences:

- A `blob_fixup` change in `extract-files.py` does **not** change what gets
  built. It changes only what a future extraction would produce, so the extracted
  tree has to be corrected by hand wherever it exists.
- Copies drift silently, with no build error to surface it.

## Measured, not assumed

| | |
|---|---|
| Files | 1623 |
| Size | 1.01 GB |
| Over GitHub's 100 MB limit | one — `radio/modem.img`, 183 MB |
| Next largest | `radio/dsp.img`, 64 MB |
| Laptop against server | **byte-identical**, 1623/1623, zero differing hashes |

Earlier figures here were wrong and are corrected above: the tree is 1.01 GB
rather than 1.8 GB, and only one file exceeds 100 MB. The second one this task
used to name, `libarcsoft_tfe_hdr.so` at 118 MB, does not exist — the largest
arcsoft blob is `libarcsoft_portrait_distortion_correction.so` at 19 MB.

There is no drift today. The check takes about a minute:

```sh
cd vendor/nothing/frogger && find . -type f | sort | xargs -P4 -n64 sha1sum
```

## Options, costed

**1. Put the tree under git.** One file needs Git LFS on GitHub, or GitLab,
which has no per-file limit. The real cost is not the first push: git stores a
whole new copy of any binary that changes, so every re-extraction that touches a
blob grows the repository permanently. For derived data that is the wrong shape,
and 1 GB is the floor rather than the size.

**2. Extract during the build.** The server has everything needed — the
`2603091830` OTA at `~/android/downloads/frogger/Frogger_B4.1-260309-1830.zip`
with `payload.bin` already unpacked beside it, and `fsck.erofs` on `PATH`. This
is the only option that fixes *both* consequences: the tree becomes derived, so
`blob_fixup` changes take effect and drift stops being possible.

Unmeasured: what it adds to every build. `extract-frogger.sh` exists to run it,
so timing it is one command — but it rewrites the tree, and a bad run leaves the
next build broken. Time it deliberately, not in passing.

**3. A manifest, checked at build time.** Store the sha1 list under `docs/data/`
— 1623 lines, about 120 KB, versioned like any other generated list — and have
the build compare the tree against it and fail loudly on a mismatch.

This does not make `blob_fixup` changes take effect; it only makes drift
impossible to miss. But it is the cheap half of option 2, it costs nothing per
build, and drift is the consequence that has actually bitten.

## Recommendation

Option 3 now, option 2 when its build cost is known. Option 1 last: storing a
gigabyte of derived binaries in git buys the least and costs the most.
