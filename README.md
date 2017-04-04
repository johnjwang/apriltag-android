# AprilTag for Android

This demo app aims to provide the same set of functionality as the
[AprilTag iOS app](https://itunes.apple.com/us/app/apriltag/id736108128).
Notably, it's still missing the ability to stream tag detections over UDP.

## Build

To build this app you will need
[Android Studio](https://developer.android.com/studio/index.html) and
Android NDK.

This repository is the <root>/app directory of the project. To create a project
and use this source,

    1) Create a new project in Android Studio,
    2) Remove the <root>/app directory,
    3) Clone this repository and move it to be <root>/app.

Resolve any build errors (notably installing CMake, updating Gradle, etc).
