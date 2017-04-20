# IgDiskCache Demo

This is a simple image feed demo explaining how to use IgDiskCache in a real application. The demo uses IgDiskCache to cache the raw image files (downloaded directly from the network), and the resized Bitmaps after decode. 

**/cache** is a simple image loading library build on top of IgDiskCache. It handles image loading asynchronously, cache the raw image to disk, and also provide an option to cache the resized **Bitmap** in memory and on disk using **BitmapCache**.

**/ui** uses a RecyclerView to show image feed on the demo app.

### Run the demo
``` sh
# Build the demo app from the 
gradle clean installdebug
# Run the demo on device 
adb shell am start -n "com.instagram.igdiskcache.demo/com.instagram.igdiskcache.demo.ui.MainActivity"
```

