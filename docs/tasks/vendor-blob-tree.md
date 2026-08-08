# Version the extracted vendor blob tree

`vendor/nothing/frogger/` is not a git repo and not a `repo` project, yet
`frogger-vendor.mk` copies files straight out of it into the image.

Consequences:

- A `blob_fixup` change in `extract-files.py` does **not** change what gets
  built. It changes only what a future extraction would produce, so the extracted
  tree has to be corrected by hand wherever it exists.
- Copies drift silently, with no build error to surface it.

## Options

1. Put the tree under git and add it to the local manifest. GitHub cannot host it
   as-is: `modem.img` (182 MB) and `libarcsoft_tfe_hdr.so` (118 MB) both exceed
   the 100 MB per-file limit. GitLab has no such limit.
2. Run `extract-files.py` as part of the build, so the tree is always derived
   rather than stored.

## Regeneration

```sh
./extract-files.py ~/sources/android/downloads/firmwares/frogger/extracted
```

Every blob entry resolves against the `2603091830` factory images alone, so no
files need to come from a live device.
