# USB Camera Recorder

Android app that shows and records USB video class (UVC) cameras to MP4, written for an analog FPV
capture dongle (goggles AV-out → Arkmicro `18ec:5555`) but meant to work with UVC cameras in general.

- USB video: [libuvc](https://github.com/libuvc/libuvc) 0.0.9 on [libusb](https://github.com/libusb/libusb) 1.0.30,
  fetched and checksum-verified by `app/src/main/cpp/CMakeLists.txt`; JNI glue in `uvccam.c`.
- Formats: MJPEG (Android's JPEG decoder) and YUY2 (via `YuvImage`). MJPEG is preferred.
- Recording: `MediaRecorder` with the phone's hardware encoder, H.264 or HEVC, to `Movies/USB Camera Recorder`.
  A foreground service and a wake lock keep it running in the background and with the screen off.
- Bitrate: the phone's own camcorder profile for the nearest resolution, scaled by pixel rate (as CameraX does).

## Automatic setup

Implemented in `CameraController.startStream()` / `check()`.

1. **Mode**: the largest supported mode up to 1080p, MJPEG over YUY2, at the camera's default frame interval.
2. **USB bandwidth**: first what the camera asks for (`dwMaxPayloadTransferSize`), then each smaller isochronous
   alternate setting, largest first. A smaller one is selected by lowering the payload size libuvc uses to pick
   the altsetting, host-side only (`set_payload_limit` in `uvccam.c`, which reads libuvc internals, hence the pin).
   Extra bandwidth does not change the picture, but too little truncates frames, and a camera may need its full
   request only for complex pictures (see Findings), so the camera's request is always tried first.
3. **Check** per attempt: skip the first frames, then judge a fixed number of frames. A frame is complete if it is
   a JPEG ending with the end-of-image marker (after trailing padding) or a YUY2 frame with all its bytes.
4. **No clean setting**: the one with the most complete frames is used. Setup only fails when no image arrives.
   Manual setups are accepted as soon as any image arrives.
5. **Stall** while streaming: the same setup is restarted first; only if it fails is its bandwidth skipped for the
   session. A running recording stays open and setup is retried until the picture returns or the camera is
   unplugged.

### What is principled and what is not

From the UVC spec or the formats, so independent of the camera:
probe order, altsetting selection, completeness by end-of-image marker / frame size, best-available fallback,
camera default frame interval, platform bitrate profiles.

Judgment calls, not tuned to any device and not validated on cameras other than the dongle
(constants in `CameraController`):

| Constant | Value | Reason |
|---|---|---|
| `FIRST_FRAME_MS` | 3000 | UVC cameras can take over a second to deliver a first frame |
| `CHECK_FRAMES` | 30 | frames judged per attempt, 1 s at 30 fps |
| incomplete limit | 1 in 20 | tolerance for a clean setting |
| `STALL_FRAMES` | 60 | frame intervals without a complete frame, 2 s at 30 fps |
| auto mode cap | 1080p | software JPEG decoding speed, estimated, not measured |

Shaped by the dongle or the test phone (Pixel 6 Pro):

| Setting | Value | Reason |
|---|---|---|
| `SKIP_FRAMES` | 3 | the dongle sometimes sends a truncated frame right after starting |
| `LIBUVC_PACKETS_PER_TRANSFER_MAX` | 8 | each isochronous transfer is one contiguous kernel allocation; 32 packets of 3 x 1000 bytes (96 KB) failed with ENOMEM at random |
| `LIBUVC_NUM_TRANSFER_BUFS` | 64 | keeps 64 ms in flight with the smaller transfers |

## Known limitations

- **Bulk-transfer cameras** (mostly USB 3): libuvc allocates `LIBUVC_NUM_TRANSFER_BUFS` buffers of the full payload
  size. A camera with a large payload (e.g. 1 MB x 64) exceeds Android's default 16 MB usbfs limit and will not start.
- Completeness checks catch truncated frames, not frames that lost packets in the middle.
- If the app process is killed while recording, that MP4 is unreadable (`MediaRecorder` writes the index at the
  end). Disconnects, encoder errors and phone shutdown finalize the file.
- No audio.

## Findings

- The dongle asks for alt 11 (3 x 1000 bytes per microframe) and delivers 29.97 fps from an NTSC source, drifting
  towards 60 fps without signal. Clean pictures give 6 to 30 KB frames, a noisy picture (pit mode, range loss)
  150 to 190 KB, and then even alt 8 and 9 deliver truncated frames: lower bandwidth fails exactly when reception
  is bad.
- Apparent "alt 11 does not work on this phone" was libusb `submiturb` failing with errno 12 (ENOMEM) due to the
  transfer size, not a USB bandwidth limit. Check `adb logcat -s libusb` before theorizing about USB failures.
