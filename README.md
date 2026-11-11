# USB Camera Recorder

Android app that shows and records USB video class (UVC) cameras to MP4. Written for recording analog FPV
goggles' AV-out through a USB capture dongle, but works with UVC webcams and capture devices in general.

- Sets the camera up automatically, with a manual override
- MJPEG and YUY2 cameras
- H.264 or HEVC with the phone's hardware encoder, at the bitrate the phone uses for its own videos
- Keeps recording in the background and with the screen off
- Saves to `Movies/USB Camera Recorder`

Requires Android 10 or newer with USB host (OTG) support.

## Build

```sh
./gradlew assembleDebug
```

Needs the Android SDK with NDK 27.1 and CMake 3.22.1. CMake downloads [libusb](https://github.com/libusb/libusb)
1.0.30 and [libuvc](https://github.com/libuvc/libuvc) 0.0.9 on the first build.

## Automatic setup

1. Picks the largest MJPEG mode up to 1080p (YUY2 if the camera has no MJPEG) at the camera's default frame rate.
2. Uses the USB bandwidth the camera asks for, then smaller isochronous alternate settings, largest first.
3. Accepts a setting when at most 1 in 20 of 30 frames arrives incomplete (a JPEG without end-of-image marker or a
   short YUY2 frame). If no setting manages that, it uses the one with the most complete frames.
4. If the stream stalls, it restarts the same setting before trying smaller ones. A recording keeps running until
   it is stopped or the camera is unplugged.

The thresholds (constants in `CameraController`) are judgment calls, so far tested with one capture dongle only.

## Limitations

- Bulk-transfer cameras with large payloads can exceed Android's 16 MB limit for USB transfer memory and fail to start.
- A recording is lost if the app process is killed while recording.
- No audio.
