# Oni: MangaDotNet

A headless background scraper extension for the **MangaClient** manga client.
Queries the mangadot.net API and returns either the full chapter list for a
manga (no images, fast) OR a single user-specified chapter with its full list
of page image URLs — always including the total chapter count so the UI can
show the available range.

## How it works

1. **Discovery** — The MangaClient main app finds this extension via the
   `com.blissless.mangaclient.EXTENSION_BEACON` broadcast receiver and the
   `"Oni: "` label prefix.
2. **Query** — The main app calls this extension's `ContentProvider` with the
   URI
   `content://com.blissless.mangadotnet.provider/scrape?manga=<name>&anilistId=<id>&chapter=<optional>`.
3. **Scrape** — A two-or-three-step pipeline over the mangadot.net API,
   branching on whether the `chapter` parameter is present:

   **Step 1 — Search (always).** A single HTTP GET finds the best-matching
   manga:

   ```
   GET https://mangadot.net/api/search?search=<url-encoded-name>
   ```

   The response's `manga_list[0]` is taken as the best match. Its `id` is
   used for the next call.

   **Step 2 — List chapters (always).** A single HTTP GET enumerates every
   chapter (metadata only — no image URLs yet):

   ```
   GET https://mangadot.net/api/manga/<id>/chapters/list
   ```

   The response is a JSON array of chapter objects, each with an `id`,
   `chapter_number`, `source` (`"user"` or `"official"`), `group_name`, and
   `scanlator_name`. The array length is recorded as `totalChapters`.

   **Step 3a — No chapter requested (fast path).** The extension builds a
   lightweight chapter list (number, title, group for each) and returns
   immediately with `{ totalChapters, chapters:[...] }`. No image fetching
   happens — this path is two HTTP calls total.

   **Step 3b — Chapter requested.** The extension finds the matching chapter
   via three-pass matching:

   1. **Exact number match** — normalized chapter number equals the request
   2. **Case-insensitive title match** — chapter's `title` field equals the
      request (lets users type "Episode 38" instead of "38")
   3. **Numeric equality** — `chapter_number` parsed as a double equals the
      request parsed as a double (handles `"1"` matching `1.0`)

   Once matched, the extension fetches only that chapter's image manifest:

   - **User-sourced chapters** (`source = "user"`) — no token required:

     ```
     GET https://mangadot.net/api/uploads/<chapterId>/images
     ```

   - **Official chapters** (`source = "official"`) — HMAC-signed token flow:

     ```
     GET https://mangadot.net/api/token/generate?chapter_id=<id>&type=chapter
     → { "page_token": "...", "signing_key": "..." }

     GET https://mangadot.net/api/chapters/<chapterId>/images
       X-Page-Token: <page_token>
       X-Nonce: <random-uuid>
       X-Timestamp: <unix-seconds>
       X-Signature: HMAC-SHA256(signing_key, "<nonce>|<timestamp>|<url>")
     ```

   The image manifest response contains an `images` array whose entries have
   relative URLs like `/chapters/manga_68/.../001.webp`. The extension
   prefixes these with the base URL to produce absolute
   `https://mangadot.net/...` URLs.

   Chapter keys are normalized chapter numbers (`"1"` instead of `"1.0"`).
   When multiple versions of the same chapter number exist (different
   scanlation groups), the group name is appended: `"1 (Asura Scans)"`.

4. **Return** — The result is serialized to JSON and returned to the main
   app. The shape depends on which path was taken (see below).

No `WebView`, no JavaScript rendering — every call is a plain HTTP GET. The
HMAC token flow for official chapters mirrors the mangadot.net web client's
JavaScript exactly, so the same images the browser would show are returned.

## Data format returned

Two response shapes, both including `totalChapters`:

### Chapter list (no `chapter` parameter)

```json
{
  "totalChapters": 105,
  "chapters": [
    { "number": "1", "title": "The Beginning", "group": "Asura Scans" },
    { "number": "2", "title": "Reunion",        "group": "Asura Scans" },
    ...
  ]
}
```

### Single chapter (`chapter` parameter set)

```json
{
  "totalChapters": 105,
  "chapter": {
    "number": "38",
    "title": "Episode 38",
    "group": "Asura Scans",
    "images": [
      "https://mangadot.net/chapters/manga_68/chapter_38_[slug]/001.webp",
      "https://mangadot.net/chapters/manga_68/chapter_38_[slug]/002.webp"
    ]
  }
}
```

### Error (with chapter count when known)

```json
{ "totalChapters": 105, "error": "Chapter '99' not found. Available range: 1–105." }
```

```json
{ "error": "No manga name provided." }
```

## Technical details

| | |
|---|---|
| **Dependencies** | Zero. Uses only `java.net.HttpURLConnection` + `org.json` + `javax.crypto.Mac`. |
| **HTTP calls per scrape** | 2 (chapter list) or 3 (single chapter — search + chapters/list + one image manifest) |
| **APK size** | ~50 KB after R8 shrinking |
| **Min Android** | API 26 |
| **Parameters read** | `manga` (English or Romaji title), `chapter` (optional — chapter number, decimal, version suffix, or title) |

## Architecture

| File | Purpose |
|------|---------|
| `MangaDotNetScraper.kt` | All HTTP + JSON parsing logic, including the three-pass chapter matcher and the HMAC token flow for official chapters. Returns a `Map<String, *>` whose shape depends on the request path. |
| `ScraperProvider.kt` | `ContentProvider` entry point. Reads the `chapter` URI parameter, passes it through, serializes the scraper result to JSON. Also logs every request and result to Logcat under tag `MangaDotNet`. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` for discovery. |

## Notes

- The scraper is **synchronous** and called from a binder thread. The main
  app is expected to call `query()` from a background coroutine.
- The "no chapter" path is intentionally fast (2 HTTP calls, no image
  fetching) so the main app can render a chapter picker UI without waiting
  on N+1 image-manifest fetches.
- Chapter-image URLs are returned as **absolute** `https://mangadot.net/...`
  URLs so the main app can load them directly.
- The `chapter` parameter accepts plain numbers (`"38"`), decimal chapters
  (`"1.5"`), version suffixes (`"12v2"`), or even full chapter titles
  (`"Episode 38"`) — the matcher tries number first, then title, then
  numeric equality.
- When the requested chapter isn't found, the error response still
  includes `totalChapters` so the UI can tell the user the valid range.

## Building

1. Place your release keystore at `app/release.jks` and add its credentials to
   `local.properties` (gitignored):

   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

2. Build the shrunk, signed APK:

   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`

3. Install alongside the MangaClient main app:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
