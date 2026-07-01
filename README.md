# Mangadotnet Extension (Oni)

Background scraper extension for the **Oni manga client** that fetches chapter
lists and per-chapter page image URLs from [mangadot.net](https://mangadot.net).

## How it works

1. The Oni Main App scans your phone for apps containing the `EXTENSION_BEACON`
   receiver.
2. When you open a manga, the Main App queries this extension's `ContentProvider`,
   passing `manga` (English/Romaji title).
3. The extension:
   - Calls `GET https://mangadot.net/api/search?search=<manga>` to find the
     best-matching title.
   - Calls `GET https://mangadot.net/api/manga/{id}/chapters/list` to enumerate
     every chapter.
   - For each chapter (up to `MAX_CHAPTERS = 100` per query), calls
     `GET https://mangadot.net/api/uploads/{id}/images` (user-sourced chapters)
     or `GET https://mangadot.net/api/chapters/{id}/images` with the HMAC-signed
     token flow (official chapters), and extracts the page image URLs.
4. Returns JSON in this shape:

```json
{
  "1": [
    "https://mangadot.net/chapters/manga_68/chapter_1_[slug]/001.webp",
    "https://mangadot.net/chapters/manga_68/chapter_1_[slug]/002.webp"
  ],
  "2": [
    "https://mangadot.net/chapters/manga_68/chapter_2_[slug]/001.webp"
  ]
}
```

Chapter keys are normalized chapter numbers (`"1"`, `"10"`, `"10.5"`). When
multiple versions of the same chapter exist (different scanlation groups), the
group name is appended: `"1 (Asura Scans)"`.

On failure, returns:

```json
{ "error": "Description of what went wrong." }
```

## Building

```bash
# (optional) set up signing for release builds
cp local.properties.example local.properties
# edit local.properties and place your keystore at app/release.jks

# build the release APK (R8-shrunk, ~40-50KB)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

If you skip the keystore setup, `./gradlew assembleRelease` still works but uses
the debug signing key — fine for testing, not for distribution.

```bash
# install on a connected device
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Architecture

| File | Purpose |
|------|---------|
| `MangaDotNetScraper.kt` | All HTTP + JSON parsing logic, including the HMAC token flow for official chapters. |
| `ScraperProvider.kt` | `ContentProvider` entry point; serializes the scraper result to JSON. Supports `Map<String, List<String>>` (chapter -> image URLs), `Map<String, Map<String,String>>` (episode -> quality -> URL), and flat `List<String>`. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` so the Main App can discover the extension. |

The extension uses **only** Android built-in APIs (`HttpURLConnection`,
`org.json`, `javax.crypto.Mac`) — no external dependencies — so the APK stays
under ~50KB after R8 shrinking.

## Notes

- The scraper is **synchronous** and called from a binder thread. The Main App
  is expected to call `query()` from a background coroutine.
- `MAX_CHAPTERS = 100` caps the number of chapters per query to avoid runaway
  HTTP. Edit the constant in `MangaDotNetScraper.kt` if you need more.
- Chapter-image URLs are returned as **absolute** `https://mangadot.net/...`
  URLs so the Main App can load them directly.