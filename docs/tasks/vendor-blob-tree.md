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

**2. Extract during the build.** The build host has everything needed — the
`2609110959` images unpacked at
`~/android/downloads/frogger/260911-0959-images/img`, and `fsck.erofs` on
`PATH`. This
is the only option that fixes *both* consequences: the tree becomes derived, so
`blob_fixup` changes take effect and drift stops being possible.

Measured: `extract-frogger.sh` takes about 80 s. It rewrites the tree, so a bad
run leaves the next build broken.

**3. A manifest, checked at build time.** Store the sha1 list under `docs/data/`
— 1623 lines, about 120 KB, versioned like any other generated list — and have
the build compare the tree against it and fail loudly on a mismatch.

This does not make `blob_fixup` changes take effect; it only makes drift
impossible to miss. But it is the cheap half of option 2, it costs nothing per
build, and drift is the consequence that has actually bitten.

## Done: option 3

`docs/data/vendor-blobs.sha1` holds sha1, size and path for all 1623 files, 166
KB. `tools/vendor-blob-guard.sh` checks the tree against it and runs from
`BoardConfig.mk`, so it covers every entry point the way the kernel config guard
does.

| Invocation | Cost | Catches |
|---|---|---|
| default | ~50 ms | added, missing or resized files |
| `--full` | ~1 s | contents that changed without changing size |
| `--generate` | ~1 min | rewrites the manifest after a re-extraction |

It reports on stderr and exits 0. Drift is loud, not fatal: a legitimate
re-extraction produces it until the manifest is regenerated, and failing at
config time would block every targeted build until someone noticed. If drift
ever reaches a shipped image, make the exit non-zero and emit `$(error)`.

## Still open: option 2

Extracting during the build is the only thing that also makes `blob_fixup`
changes take effect. The build host has the images and `fsck.erofs`. A run of
`extract-frogger.sh` there took about 80 s, copying the images included, so the
cost per build is small. It still rewrites the tree, so a bad run leaves the
next build broken.

Option 1 stays last: storing a gigabyte of derived binaries in git buys the
least and costs the most.
