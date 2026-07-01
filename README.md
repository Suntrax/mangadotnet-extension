# Oni: MangaDotNet

A headless background scraper extension for the **MangaClient** manga client.
Queries the mangadot.net API and returns the image URLs for a single
user-specified chapter — always including the total chapter count so the UI
can show the available range.

## How it works

1. **Discovery** — The MangaClient main app finds this extension via the
   `com.blissless.mangaclient.EXTENSION_BEACON` broadcast receiver and the
   `"Oni: "` label prefix.
2. **Query** — The main app calls this extension's `ContentProvider` with the
   URI
   `content://com.blissless.mangadotnet.provider/scrape?manga=<name>&anilistId=<id>&chapter=<required>`.
   The `chapter` parameter is **required** — the extension does not support a
   "list all chapters" mode. If `chapter` is missing, the extension returns an
   error immediately.
3. **Scrape** — A three-step pipeline over the mangadot.net API:

   **Step 1 — Search.** A single HTTP GET finds the best-matching manga:

   ```
   GET https://mangadot.net/api/search?search=<url-encoded-name>
   ```

   The response's `manga_list[0]` is taken as the best match. Its `id` is
   used for the next call.

   **Step 2 — List chapters.** A single HTTP GET enumerates every chapter
   (metadata only — no image URLs yet):

   ```
   GET https://mangadot.net/api/manga/<id>/chapters/list
   ```

   The response is a JSON array of chapter objects, each with an `id`,
   `chapter_number`, `source` (`"user"` or `"official"`), `group_name`, and
   `scanlator_name`. The array length is recorded as `totalChapters`.

   **Step 3 — Find + fetch the requested chapter.** The extension finds the
   matching chapter via a three-pass matcher:

   1. **Exact number match** — normalized chapter number equals the request
   2. **Case-insensitive title match** — chapter's `title` field equals the
      request (lets users type "Episode 38" instead of "38")
   3. **Numeric equality** — `chapter_number` parsed as a double equals the
      request parsed as a double (handles `"1"` matching `1.0`)

   Once matched, the extension fetches that chapter's image manifest:

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
   app.

No `WebView`, no JavaScript rendering — every call is a plain HTTP GET. The
HMAC token flow for official chapters mirrors the mangadot.net web client's
JavaScript exactly, so the same images the browser would show are returned.

## Data format returned

### Success (chapter found)

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

### Error — no chapter provided

```json
{ "error": "No chapter provided. This extension requires a chapter number (e.g. '1', '1.5', '12v2') or a chapter title (e.g. 'Episode 38')." }
```

### Error — chapter not found (totalChapters still included so the UI can show the valid range)

```json
{ "totalChapters": 105, "error": "Chapter '99' not found. Available range: 1–105." }
```

### Error — other failures

```json
{ "error": "No manga name provided." }
{ "error": "No manga found for '<name>'." }
{ "error": "Failed to list chapters: <network error>" }
{ "error": "Failed to fetch chapter <n> images: <network error>" }
{ "error": "Chapter <n> returned no images." }
```

## Technical details

| | |
|---|---|
| **Dependencies** | Zero. Uses only `java.net.HttpURLConnection` + `org.json` + `javax.crypto.Mac`. |
| **HTTP calls per scrape** | 3 (search + chapters/list + one image manifest) |
| **APK size** | ~50 KB after R8 shrinking |
| **Min Android** | API 26 |
| **Parameters read** | `manga` (English or Romaji title), `chapter` (**required** — chapter number, decimal, version suffix, or title) |

## Architecture

| File | Purpose |
|------|---------|
| `MangaDotNetScraper.kt` | All HTTP + JSON parsing logic, including the three-pass chapter matcher and the HMAC token flow for official chapters. Returns a `Map<String, *>` with `totalChapters` and either `chapter` (success) or `error` (failure). |
| `ScraperProvider.kt` | `ContentProvider` entry point. Reads the `chapter` URI parameter, passes it through, serializes the scraper result to JSON. Also logs every request and result to Logcat under tag `MangaDotNet`. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` for discovery. |

## Notes

- The scraper is **synchronous** and called from a binder thread. The main
  app is expected to call `query()` from a background coroutine.
- The `chapter` parameter is **required**. The extension does not support a
  "list all chapters" mode — if you don't know the chapter number, guess
  `"1"` and the error response will tell you the available range
  (`"Available range: 1–105."`).
- The `chapter` parameter accepts plain numbers (`"38"`), decimal chapters
  (`"1.5"`), version suffixes (`"12v2"`), or full chapter titles
  (`"Episode 38"`) — the matcher tries number first, then title, then
  numeric equality.
- When the requested chapter isn't found, the error response still includes
  `totalChapters` so the UI can tell the user the valid range.
- Chapter-image URLs are returned as **absolute** `https://mangadot.net/...`
  URLs so the main app can load them directly.

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
