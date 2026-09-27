# Vendored artifacts — patches

## play-services-home 17.1.0 — coroutines `$default` bridge retarget (2026-09-27)

**Problem:** the SDK was compiled against a kotlinx-coroutines build that uses
`-Xjvm-default=all`, so 4 classes reference synthetic default-argument bridges
on the coroutines *interfaces*:

- `kotlinx/coroutines/Job.cancel$default(...)`
- `kotlinx/coroutines/sync/Mutex.unlock$default(...)`

Every coroutines release on Maven Central (verified 1.6.4 through 1.10.2) is
built with the default `jvm-default=disable`, where those bridges live on
`Job$DefaultImpls` / `Mutex$DefaultImpls` instead. At runtime this throws
`NoSuchMethodError` — observed as a crash when tapping the final "Allow" in
the Google Home permission flow (`zzbu.invokeSuspend` → `Mutex.unlock$default`).

**Fix:** constant-pool patch of the 4 affected classes inside the AAR's
`classes.jar`, retargeting the two method refs from the interface to the
matching `$DefaultImpls` class. Descriptors are unchanged and the DefaultImpls
bridge does exactly what the interface bridge would do (fill in default args,
then delegate), so behavior is identical. Patched classes:

- `com/google/android/gms/internal/home/zzbu.class` (the reported crash)
- `com/google/home/Home.class`
- `com/google/home/Home$fetchAndUpdateTokenForAccount$2.class`
- `com/google/home/Home$handleConnectionEvent$2.class`

**Audit:** all 91 symbolic references from the AAR to `kotlinx/coroutines/**`
were checked against coroutines 1.9.0 (the version the app uses). The 4 refs
above were the only genuine binary incompatibilities; the rest resolve via
normal interface inheritance. `play-services-home-types` 17.1.0 is clean.

**Reproducing:** `python3 /tmp/patch_aar.py` is ephemeral; the procedure is:
unzip `classes.jar` from the AAR, and in each affected class replace the
`CONSTANT_Methodref` owner `kotlinx/coroutines/Job` → `.../Job$DefaultImpls`
(for `cancel$default`) and `kotlinx/coroutines/sync/Mutex` →
`.../Mutex$DefaultImpls` (for `unlock$default`), re-zip.

**If Google ships a newer SDK:** re-run the audit on the new AAR before
vendoring it; if the new build no longer references interface `$default`
bridges, the pristine AAR can be used as-is.
