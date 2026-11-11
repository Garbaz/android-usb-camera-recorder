cmake_minimum_required(VERSION 3.22.1)
project(uvccam C)

include(FetchContent)

# libusb has no CMake build; compile the same sources as its android/jni/libusb.mk.
FetchContent_Declare(libusb
  URL https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2
  URL_HASH SHA256=fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf)
FetchContent_MakeAvailable(libusb)

set(LIBUSB_SRC ${libusb_SOURCE_DIR}/libusb)
add_library(usb-1.0 STATIC
  ${LIBUSB_SRC}/core.c
  ${LIBUSB_SRC}/descriptor.c
  ${LIBUSB_SRC}/hotplug.c
  ${LIBUSB_SRC}/io.c
  ${LIBUSB_SRC}/sync.c
  ${LIBUSB_SRC}/strerror.c
  ${LIBUSB_SRC}/os/linux_usbfs.c
  ${LIBUSB_SRC}/os/events_posix.c
  ${LIBUSB_SRC}/os/threads_posix.c
  ${LIBUSB_SRC}/os/linux_netlink.c)
target_include_directories(usb-1.0
  PRIVATE ${libusb_SOURCE_DIR}/android ${LIBUSB_SRC}/os
  PUBLIC $<BUILD_INTERFACE:${LIBUSB_SRC}>)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PUBLIC log)
# libuvc's FindLibUSB uses this target when it exists instead of pkg-config.
add_library(LibUSB::LibUSB ALIAS usb-1.0)
# libuvc exports its static library, which pulls libusb into the export set.
install(TARGETS usb-1.0 EXPORT libuvcTargets ARCHIVE DESTINATION lib)

set(CMAKE_BUILD_TARGET Static CACHE STRING "" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DISABLE_JPEG ON CACHE BOOL "" FORCE)
# Each isochronous transfer is one contiguous kernel allocation (usbfs). 32 packets of up to
# 3 x 1024 bytes need ~96 KB, which fragmented phones refuse with ENOMEM at random; 8 packets keep
# it at 24 KB. 64 transfers of 8 microframes keep 64 ms in flight.
set(LIBUVC_PACKETS_PER_TRANSFER_MAX 8 CACHE STRING "" FORCE)
set(LIBUVC_NUM_TRANSFER_BUFS 64 CACHE STRING "" FORCE)
FetchContent_Declare(libuvc
  URL https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.9.tar.gz
  URL_HASH SHA256=825775b21b8558ac2afcbc2561ab1e5081898c9b0296673fcaa7cabb6e94078e)
FetchContent_MakeAvailable(libuvc)

add_library(uvccam SHARED uvccam.c)
target_link_libraries(uvccam PRIVATE LibUVC::UVCStatic usb-1.0 log)
