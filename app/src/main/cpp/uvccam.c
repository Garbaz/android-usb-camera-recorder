// JNI glue between UvcNative.kt and libuvc/libusb.
//
// Android hands us an already-opened usbfs file descriptor (UsbDeviceConnection),
// which libusb wraps without doing its own device discovery.

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <libusb.h>
#include <libuvc/libuvc.h>
// Only for uvc_stream_handle.cur_ctrl, see set_payload_limit(). libuvc is pinned.
#include <libuvc/libuvc_internal.h>

typedef struct {
  uvc_context_t *ctx;
  uvc_device_handle_t *devh;
  uvc_stream_handle_t *strmh;
  jobject sink;
  jmethodID on_frame;
  jbyteArray buf;
  jsize buf_len;
} cam_t;

static JavaVM *jvm;
static pthread_key_t detach_key;

static void detach_thread(void *unused) { (*jvm)->DetachCurrentThread(jvm); }

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  jvm = vm;
  pthread_key_create(&detach_key, detach_thread);
  // Enumerating /dev/bus/usb is not permitted for apps; devices come from Java.
  libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
  // Transfer and interface errors go to logcat (tag "libusb").
  libusb_set_option(NULL, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
  return JNI_VERSION_1_6;
}

static void throw_io(JNIEnv *env, const char *what, int err) {
  char msg[160];
  snprintf(msg, sizeof msg, "%s: %s (%d)", what, uvc_strerror(err), err);
  (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
}

static JNIEnv *attached_env(void) {
  JNIEnv *env;
  if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
  (*jvm)->AttachCurrentThread(jvm, &env, NULL);
  // libuvc's callback thread ends when the stream stops; detach it then.
  pthread_setspecific(detach_key, env);
  return env;
}

static void frame_cb(uvc_frame_t *frame, void *user) {
  cam_t *cam = user;
  JNIEnv *env = attached_env();
  jsize n = (jsize)frame->data_bytes;
  if (n > cam->buf_len) {
    (*env)->DeleteGlobalRef(env, cam->buf);
    jbyteArray local = (*env)->NewByteArray(env, n);
    cam->buf = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    cam->buf_len = n;
  }
  (*env)->SetByteArrayRegion(env, cam->buf, 0, n, frame->data);
  (*env)->CallVoidMethod(env, cam->sink, cam->on_frame, cam->buf, n);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
  }
}

JNIEXPORT jlong JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_open(JNIEnv *env, jobject thiz, jint fd) {
  cam_t *cam = calloc(1, sizeof *cam);
  int res = uvc_init(&cam->ctx, NULL);
  if (res != UVC_SUCCESS) {
    free(cam);
    throw_io(env, "uvc_init", res);
    return 0;
  }
  res = uvc_wrap(fd, cam->ctx, &cam->devh);
  if (res != UVC_SUCCESS) {
    uvc_exit(cam->ctx);
    free(cam);
    throw_io(env, "uvc_wrap", res);
    return 0;
  }
  return (jlong)(intptr_t)cam;
}

// One record per frame descriptor:
// formatIndex, subtype, fourcc, frameIndex, width, height, defaultInterval, n, interval[n]
JNIEXPORT jintArray JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_formats(JNIEnv *env, jobject thiz, jlong h) {
  cam_t *cam = (cam_t *)(intptr_t)h;
  size_t len = 0, cap = 64;
  jint *out = malloc(cap * sizeof *out);
#define PUT(v) do { if (len == cap) out = realloc(out, (cap *= 2) * sizeof *out); out[len++] = (jint)(v); } while (0)
  for (const uvc_format_desc_t *fmt = uvc_get_format_descs(cam->devh); fmt; fmt = fmt->next) {
    jint fourcc;
    memcpy(&fourcc, fmt->fourccFormat, 4);
    for (const uvc_frame_desc_t *fr = fmt->frame_descs; fr; fr = fr->next) {
      PUT(fmt->bFormatIndex);
      PUT(fmt->bDescriptorSubtype);
      PUT(fourcc);
      PUT(fr->bFrameIndex);
      PUT(fr->wWidth);
      PUT(fr->wHeight);
      PUT(fr->dwDefaultFrameInterval);
      if (fr->intervals) {
        size_t n = 0;
        while (fr->intervals[n]) n++;
        PUT(n);
        for (size_t i = 0; i < n; i++) PUT(fr->intervals[i]);
      } else {  // continuous range: offer its ends
        PUT(2);
        PUT(fr->dwMinFrameInterval);
        PUT(fr->dwMaxFrameInterval);
      }
    }
  }
#undef PUT
  jintArray arr = (*env)->NewIntArray(env, (jsize)len);
  (*env)->SetIntArrayRegion(env, arr, 0, (jsize)len, out);
  free(out);
  return arr;
}

// Isochronous IN endpoints of the video streaming interfaces:
// interfaceNumber, altSetting, bytesPerInterval, transactionsPerInterval (per record)
JNIEXPORT jintArray JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_altSettings(JNIEnv *env, jobject thiz, jlong h) {
  cam_t *cam = (cam_t *)(intptr_t)h;
  libusb_device *dev = libusb_get_device(uvc_get_libusb_handle(cam->devh));
  struct libusb_config_descriptor *cfg;
  jint out[4 * 64];
  jsize len = 0;
  if (libusb_get_active_config_descriptor(dev, &cfg) == 0) {
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
      const struct libusb_interface *itf = &cfg->interface[i];
      for (int a = 0; a < itf->num_altsetting && len < 4 * 64; a++) {
        const struct libusb_interface_descriptor *alt = &itf->altsetting[a];
        if (alt->bInterfaceClass != LIBUSB_CLASS_VIDEO || alt->bInterfaceSubClass != 2) continue;
        for (int e = 0; e < alt->bNumEndpoints; e++) {
          const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
          if ((ep->bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS ||
              !(ep->bEndpointAddress & LIBUSB_ENDPOINT_IN))
            continue;
          // Same computation libuvc uses to pick the altsetting.
          int transactions = ((ep->wMaxPacketSize >> 11) & 3) + 1;
          int bytes = (ep->wMaxPacketSize & 0x7ff) * transactions;
          struct libusb_ss_endpoint_companion_descriptor *ss = NULL;
          libusb_get_ss_endpoint_companion_descriptor(NULL, ep, &ss);
          if (ss) {  // SuperSpeed: bursts instead of high-bandwidth transactions
            bytes = ss->wBytesPerInterval;
            transactions = 1;
            libusb_free_ss_endpoint_companion_descriptor(ss);
          }
          out[len++] = alt->bInterfaceNumber;
          out[len++] = alt->bAlternateSetting;
          out[len++] = bytes;
          out[len++] = transactions;
          break;
        }
      }
    }
    libusb_free_config_descriptor(cfg);
  }
  jintArray arr = (*env)->NewIntArray(env, len);
  (*env)->SetIntArrayRegion(env, arr, 0, len, out);
  return arr;
}

// libuvc picks the first altsetting whose packets hold dwMaxPayloadTransferSize.
// Lowering the value host-side selects a smaller altsetting without changing
// what was committed to the device (the field is device-owned per the UVC spec).
static void set_payload_limit(uvc_stream_handle_t *strmh, uint32_t bytes) {
  strmh->cur_ctrl.dwMaxPayloadTransferSize = bytes;
}

static void release_stream(JNIEnv *env, cam_t *cam) {
  if (cam->strmh) {
    uvc_stream_stop(cam->strmh);  // joins the callback thread
    uvc_stream_close(cam->strmh);
    cam->strmh = NULL;
  }
  if (cam->sink) (*env)->DeleteGlobalRef(env, cam->sink);
  if (cam->buf) (*env)->DeleteGlobalRef(env, cam->buf);
  cam->sink = NULL;
  cam->buf = NULL;
  cam->buf_len = 0;
}

// Returns the payload size the device asked for (bytes per microframe).
JNIEXPORT jint JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_start(
    JNIEnv *env, jobject thiz, jlong h, jint formatIndex, jint width, jint height,
    jint fps, jint maxPayload, jobject sink) {
  cam_t *cam = (cam_t *)(intptr_t)h;
  const uvc_format_desc_t *fmt = uvc_get_format_descs(cam->devh);
  while (fmt && fmt->bFormatIndex != formatIndex) fmt = fmt->next;
  if (!fmt) {
    throw_io(env, "format index", UVC_ERROR_INVALID_PARAM);
    return 0;
  }
  uvc_stream_ctrl_t ctrl;
  int res = uvc_get_stream_ctrl_format_size(
      cam->devh, &ctrl, uvc_frame_format_for_guid((uint8_t *)fmt->guidFormat), width, height, fps);
  if (res != UVC_SUCCESS) {
    throw_io(env, "negotiate", res);
    return 0;
  }
  jint requested = (jint)ctrl.dwMaxPayloadTransferSize;

  res = uvc_stream_open_ctrl(cam->devh, &cam->strmh, &ctrl);
  if (res != UVC_SUCCESS) {
    cam->strmh = NULL;
    throw_io(env, "open stream", res);
    return 0;
  }
  if (maxPayload > 0 && (uint32_t)maxPayload < ctrl.dwMaxPayloadTransferSize)
    set_payload_limit(cam->strmh, (uint32_t)maxPayload);

  cam->sink = (*env)->NewGlobalRef(env, sink);
  cam->on_frame = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, sink), "onFrame", "([BI)V");
  cam->buf_len = (jsize)ctrl.dwMaxVideoFrameSize;
  jbyteArray local = (*env)->NewByteArray(env, cam->buf_len);
  cam->buf = (*env)->NewGlobalRef(env, local);
  (*env)->DeleteLocalRef(env, local);

  res = uvc_stream_start(cam->strmh, frame_cb, cam, 0);
  if (res != UVC_SUCCESS) {
    release_stream(env, cam);
    throw_io(env, "start stream", res);
    return 0;
  }
  return requested;
}

JNIEXPORT void JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_stop(JNIEnv *env, jobject thiz, jlong h) {
  release_stream(env, (cam_t *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_io_github_garbaz_usbcamerarecorder_UvcNative_close(JNIEnv *env, jobject thiz, jlong h) {
  cam_t *cam = (cam_t *)(intptr_t)h;
  release_stream(env, cam);
  uvc_close(cam->devh);
  uvc_exit(cam->ctx);
  free(cam);
}
