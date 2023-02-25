# AprilTag for Android

This demo app aims to provide the same set of functionality as the
[AprilTag iOS app](https://itunes.apple.com/us/app/apriltag/id736108128).

It supports multiple tag families, but runs an older version of the 
AprilTag libraries.

Notably, it's still missing the ability to stream tag detections over UDP.

## Build

To build this app you will need
[Android Studio](https://developer.android.com/studio/index.html).

In Android Studio:
    1) Open a project at the root of this repository,
    2) Build the target and ensure the process succeeds.

## Testing

To facilitate testing, it is useful to have a virtual emulated scene
with tags present. Follow [these instructions](https://developers.google.com/ar/develop/java/emulator#move_the_virtual_camera)
to add a picture of your choice to the virtual scene.