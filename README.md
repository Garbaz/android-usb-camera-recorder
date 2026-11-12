# <img src="fastlane/metadata/android/en-US/images/icon.png" alt="" width="48" align="absmiddle"> USB Camera Recorder

[![GitHub](https://img.shields.io/badge/GitHub-Garbaz%2Fusb--camera--recorder-blue?logo=github)](https://github.com/Garbaz/usb-camera-recorder)
[![Release](https://img.shields.io/github/v/release/Garbaz/usb-camera-recorder)](https://github.com/Garbaz/usb-camera-recorder/releases/latest)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![Build](https://img.shields.io/github/actions/workflow/status/Garbaz/usb-camera-recorder/build.yml?branch=main&label=build)](https://github.com/Garbaz/usb-camera-recorder/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/Garbaz/usb-camera-recorder)](LICENSE)

Android app that shows and records USB video class (UVC) cameras, like webcams and video capture devices, to MP4.

- **Automatic camera setup**: the USB bandwidth the camera asks for, or the highest below it at which the phone
  receives complete frames, with manual setup as a fallback
- **MP4 recording**: H.264 or HEVC at the phone's own video quality, with the camera's audio
- **Background recording**: continues with the screen off or another app open
- **Auto split**: records while there is a picture and starts a new file after a few seconds without one, e.g. one
  file per FPV flight
- **Save folder**: Movies by default, or any folder you pick

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" alt="Camera view" height="560">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" alt="Settings" height="560">
</p>

## Install

Requires Android 10 or newer with USB host (OTG) support. Download the APK from
[Releases](https://github.com/Garbaz/usb-camera-recorder/releases).

## Usage

Plug in the camera and allow the app to use it. The record button starts and stops a recording; with Auto split
switched on, it starts a session that records whenever there is a picture.

If the picture stays black or broken, set the camera to Manual in Settings and try a lower USB bandwidth.
Please [open an issue](https://github.com/Garbaz/usb-camera-recorder/issues) with the camera and phone
model if that was needed.

## Build

```sh
git clone --recurse-submodules https://github.com/Garbaz/usb-camera-recorder.git
cd usb-camera-recorder
./gradlew assembleDebug
```

Needs the Android SDK with NDK 27.1 and CMake 3.22.1. [libusb](https://github.com/libusb/libusb) and
[libuvc](https://github.com/libuvc/libuvc) are git submodules in `app/src/main/cpp/external`.

## License

GNU General Public License v3 or later, see [LICENSE](LICENSE).

The app bundles [libusb](https://libusb.info) (LGPL-2.1-or-later) and [libuvc](https://github.com/libuvc/libuvc)
(BSD-3-Clause).
