# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.

cmake_minimum_required(VERSION 3.4.1)
project(Project)

# Specifies a library name, specifies whether the library is STATIC or
# SHARED, and provides relative paths to the source code. You can
# define multiple libraries by adding multiple add.library() commands,
# and CMake builds them for you. When you build your app, Gradle
# automatically packages shared libraries with your APK.

add_library(# Specifies the name of the library.
            apriltag

            # Sets the library as a shared library.
            SHARED

            # Provides a relative path to your source file(s).
            src/main/apriltag/apriltag.c
            src/main/apriltag/apriltag_jni.c
            src/main/apriltag/apriltag_quad_thresh.c
            src/main/apriltag/common/g2d.c
            src/main/apriltag/common/getopt.c
            src/main/apriltag/common/homography.c
            src/main/apriltag/common/image_f32.c
            src/main/apriltag/common/image_u8.c
            src/main/apriltag/common/image_u8x3.c
            src/main/apriltag/common/matd.c
            src/main/apriltag/common/pnm.c
            src/main/apriltag/common/string_util.c
            src/main/apriltag/common/svd22.c
            src/main/apriltag/common/time_util.c
            src/main/apriltag/common/unionfind.c
            src/main/apriltag/common/workerpool.c
            src/main/apriltag/common/zarray.c
            src/main/apriltag/common/zhash.c
            src/main/apriltag/common/zmaxheap.c
            src/main/apriltag/tag16h5.c
            src/main/apriltag/tag25h7.c
            src/main/apriltag/tag25h9.c
            src/main/apriltag/tag36artoolkit.c
            src/main/apriltag/tag36h10.c
            src/main/apriltag/tag36h11.c
            )

STRING(REPLACE "-O0" "-O4" CMAKE_C_FLAGS_DEBUG ${CMAKE_C_FLAGS_DEBUG})
STRING(REPLACE "-O2" "-O4" CMAKE_C_FLAGS_RELEASE ${CMAKE_C_FLAGS_RELEASE})

include_directories(src/main/apriltag/)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(apriltag ${log-lib} ${jnigraphics-lib})
