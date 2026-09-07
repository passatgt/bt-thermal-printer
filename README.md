# Thermal Print

A small Android app for printing PDFs and images on a 58mm Bluetooth thermal
printer (tested against an **MPT-II** over Bluetooth Classic / SPP).

Thermal printers can't take PDFs, so the app rasterises everything to a 1-bit
image at exactly the printer's dot width and ships it as ESC/POS raster data.

## What it does

- **Trims margins.** Every page is scanned for where the ink actually is, then
  re-rendered so that content fills the full 48mm printable area. An A4 page with
  2cm margins comes out full-width instead of postage-stamp sized.
- **Keeps coloured content.** Plain luminance discards saturated colour — yellow
  measures 227 out of 255, brighter than any usable black/white cutoff — so coloured
  headings and logos would print as blank paper. For text and line art, pixels are
  pulled towards their darkest channel in proportion to saturation, so a vivid colour
  becomes ink at any brightness, while near-neutral tints (pale highlights, light
  table fills) stay white and the black text on them stays readable. Photographs are
  measured by tone instead, or every saturated area would collapse into a silhouette.
- **Picks its own rendering.** *Auto* looks at how many mid-greys a page contains:
  text and line art sit almost entirely at the two extremes, photographs live in the
  middle. Sharp thresholding for the former, Floyd–Steinberg dithering for the latter,
  decided per page — so a photo embedded in a PDF gets dithered too.
- **Renders at native resolution.** Pages are rasterised straight at 203 dpi via a
  transform on `PdfRenderer`, rather than downsampled from a big bitmap, so small
  text stays readable.
- **Shows up as a system printer.** A `PrintService` advertises the paired printer
  to Android, so anything with a Print menu (Chrome, Drive, Gmail, Photos) can
  reach it.
- **Accepts shares.** PDFs and images sent via the share sheet or "Open with" open
  a preview screen with a Print button.
- **Falls back to BLE.** Classic SPP is tried first; printers that only expose a
  BLE serial characteristic are handled too.

## Using it

1. Open the app, tap **Pick printer**, tap **Scan**, choose your printer
   (pairing PIN is usually `0000` or `1234`).
2. **Test print** prints a strip with a width ruler and a grey ramp — handy for
   checking that the paper width and darkness settings are right.
3. For system-wide printing, tap **Open print settings** and switch
   *Thermal Print (58mm)* on. It then appears in any app's print dialog.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Paper width | 58 mm (384 dots) | 80 mm (576 dots) also available |
| Image style | Auto | Per-page guess. Override with *Sharp* or *Dithered*. |
| Trim margins | on | Off prints the page as laid out |
| Darkness | +25 | Shifts the black/white cutoff |
| Feed after print | 4 lines | So the last line clears the tear-off edge |

## Layout

```
Settings.kt                   persisted state (SharedPreferences)
pdf/Rasterizer.kt             PDF/image -> cropped, scaled, 1-bit raster
pdf/MonoBitmap.kt             1bpp buffer in ESC/POS layout
pdf/DocumentRasterizer.kt     entry point + the self-test page
escpos/EscPos.kt              command bytes, GS v 0 raster banding
bt/SppTransport.kt            Bluetooth Classic RFCOMM (3 connect strategies)
bt/BleTransport.kt            BLE GATT fallback
bt/ThermalPrinter.kt          job sequencing and write pacing
bt/BtScanner.kt               Classic + LE discovery and pairing
print/ThermalPrintService.kt  Android PrintService integration
ui/                           Compose UI
```

## Building

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified and signed from a `keystore.properties` in the repo root
(gitignored) pointing at a keystore kept outside the tree:

```
storeFile=/path/to/bt-thermal-printer.jks
storePassword=...
keyAlias=thermalprint
keyPassword=...
```

Without that file the project still builds — the release APK just comes out unsigned.

## Notes on the printing path

- Raster data goes out in 64-row bands (`GS v 0`), each followed by a short sleep
  proportional to the row count. These printers rarely apply real flow control, so
  writes are paced to the mechanism instead of trusting the socket.
- `ESC 3 0` (zero line spacing) is sent up front, otherwise many printers insert
  their default feed between bands and the image comes out striped.
- The print service advertises **48 × 68 mm** as its default page. The ratio is not
  cosmetic: apps that print images use `PrintHelper`, whose default `SCALE_MODE_FILL`
  scales a photo to *cover* the page and crops the overflow. The 48 × 297 mm page this
  originally defaulted to threw away about 78% of a photo's width. 48 × 68 mm is the
  A-series ratio at this width, so A4 pages map on with no letterboxing and 4:3 photos
  lose about 6%. Longer pages remain selectable for continuous receipts — and for a
  landscape photo, set Orientation to landscape in the print dialog.
- SPP connection is attempted three ways — secure RFCOMM to the SPP service
  record, insecure RFCOMM, then a reflective `createRfcommSocket(1)` — because
  cheap printers often ship a broken or missing service record.
