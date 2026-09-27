# Vendored Google Home APIs SDK

Google does **not** publish `play-services-home` / `play-services-home-types`
on public Maven repositories (verified: neither `google()` nor Maven Central
carries them). The SDK is distributed as AARs from the
[Google Home developer console](https://developers.home.google.com/) —
downloading them requires signing in to Google Home Developers.

## Layout

Each artifact lives at the standard Maven path with a minimal POM next to
the AAR (the `-types` POM declares its compile dependency on `-home`,
mirroring the real artifacts):

```
mavenLocal/
  com/google/android/gms/play-services-home/17.1.0/
    play-services-home-17.1.0.aar
    play-services-home-17.1.0.pom
  com/google/android/gms/play-services-home-types/17.1.0/
    play-services-home-types-17.1.0.aar
    play-services-home-types-17.1.0.pom
```

## Updating the SDK

1. Download the new `play-services-home-<version>.aar` and
   `play-services-home-types-<version>.aar` from the developer console.
2. Drop them into the matching versioned directories (create them).
3. Copy the POMs, renaming the version inside and in the filenames.
4. Bump the versions in `app/build.gradle.kts`.

Both artifacts must always be updated together.
