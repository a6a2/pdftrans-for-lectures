# PdfTrans for Android

Translate English PDFs into Arabic on your phone. Open source (MIT), no server of your own.

## What changed in 1.1 (why results were corrupt or crowded before)
The old version translated **one PDF line at a time** and squeezed the Arabic under each line. Sentences were cut
into fragments (bad Arabic) and the Arabic collided with the next line (crowded pages). Now:

1. **Paragraphs, not lines.** Lines are grouped into paragraphs by geometry (spacing, indentation, bullets, columns), put in
   reading order (columns, headers and footers handled), classified (heading, list item, text) and translated as whole
   paragraphs, split into sentences and re-packed to each engine's size limit.
2. **Layouts that cannot overlap** (see below), with an automatic per-page fallback.
3. **Quality pipeline:** URLs, e-mails and your glossary terms are protected; Arabic punctuation is fixed (`،` `؛` `؟`);
   optional Arabic-Indic digits; every paragraph is judged (blank, still English, truncated, looping) and doubtful ones
   are re-translated by another engine. If an engine is down or out of quota the run moves to the next one.
4. **Readable Arabic:** never below 8 pt, same size as the English (was 0.9x and shrinking to 5 pt), 1.3 line spacing,
   IBM Plex Sans Arabic for small text and Amiri for large text (Auto).

## Layouts
| Layout | What you get |
|---|---|
| **Facing pages** (default) | the original page, then an Arabic page of the same size in reading order. Never overlaps; prints well |
| **Under each paragraph** | Arabic under each paragraph; the page is cut on blank scan-lines and made taller. Falls back to facing for a page with columns/tables or no clean cut |
| **Replace the text** | Arabic drawn over each block on a patch of the sampled background colour. Falls back to facing for a page with a busy background or when the Arabic can't fit at a readable size |
| **Paragraph** | the document rebuilt as text: each paragraph, then its Arabic |

Pages that had to fall back are listed in the notes after the run.

## Engines
| Engine | Where text goes | Notes |
|---|---|---|
| On device (ML Kit) | stays on the phone | one-time ~30 MB Arabic model, then offline. Optional: *Retry weak paragraphs online* |
| **Microsoft (free)** | Microsoft | no key, good Arabic, through the token the Edge browser uses. Unofficial, opt-in |
| Google (unofficial) | Google | opt-in; sends a browser User-Agent and has a second endpoint for HTTP 429 |
| **LibreTranslate (free servers)** | public servers (`translate.adminforge.de`, `lt.pyrine.net`) | no key, lower quality; leave the address empty, or enter your own server |
| MyMemory | MyMemory | official free API, ~5k chars/day (50k with an e-mail in Settings) |

Lingva was evaluated and left out: public instances were reported returning untranslated text in autumn 2026.
Unofficial endpoints can change at any time; the app switches to the next engine when one stops answering.

Scanned PDFs are read with on-device OCR (ML Kit, Latin). Pages can be limited with `1-10,15,20-`.

## Build the APK without a PC
1. Create a GitHub repository and upload this folder.
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `PdfTrans-apk` artifact, unzip, install `app-release.apk`.

The workflow also runs the JVM unit tests (`gradle :app:testDebugUnitTest`) as a separate job that never blocks the APK.

## Code map
| Area | File |
|---|---|
| text rules, token protection, Arabic polish, quality judge (pure Kotlin, tested) | `Text.kt` |
| line -> paragraph grouping, reading order, headings, running heads (pure Kotlin, tested) | `pdf/Blocks.kt` |
| engine base class + `TranslationService` (chunking, retry, fallback; pure Kotlin, tested) | `engine/Core.kt` |
| ML Kit, Microsoft, Google, LibreTranslate, MyMemory engines | `engine/Engines.kt` |
| PDF text and OCR extraction | `pdf/Extract.kt` |
| facing / under-paragraph / replace / paragraph rendering | `pdf/Render.kt` |
| orchestration | `pdf/Pipeline.kt` |
| UI | `ui/Screens.kt`, `ui/Components.kt`, `ui/Strings.kt` |

Arabic shaping and right-to-left layout are done by Android's text engine (`StaticLayout`), then merged onto the original
pages with PDFBox, so original pages stay vector.

## What was verified, and what was not
- The pure logic (`Text.kt`, `pdf/Blocks.kt`, `engine/Core.kt`) is compiled and unit-tested on a JVM (`app/src/test`, 25 tests),
  and the paragraph grouping was run on a generated PDF with headings, justified text, bullets, two columns and footers.
- The Android-facing code (`Render.kt`, `Extract.kt`, `Pipeline.kt`, `Engines.kt`) type-checks against the real Android SDK
  and the real PDFBox-Android 2.0.27 sources, with small stubs only for ML Kit.
- **Not verified:** the Compose UI, the full Gradle build, a run on a device, and the live free endpoints (Microsoft token,
  LibreTranslate servers). Their addresses and formats follow what other open-source clients use in 2026 but could not be called
  from the build sandbox. If the first CI build or a first run reports a problem, send me the log.

## Known limits
- No fully-local *high quality* engine yet. ML Kit is a small model. A bigger offline model (NLLB-200 distilled or Opus-MT via
  ONNX Runtime / CTranslate2) fits behind the same `Engine` class but needs a model download, a tokenizer and on-device testing.
- ML Kit is Google's closed-source library, so the app can't go on F-Droid as is.
- Translating a long file needs the app to stay open; there is no background service yet.
- *Replace* mode leaves the original text in the file under the patch (it is hidden, still selectable).
- The release APK is signed with the debug key so it installs directly. Use your own keystore before distributing it.

## Licences
App: MIT. PDFBox-Android: Apache-2.0. Fonts: SIL OFL. ML Kit: Google ML Kit terms.
