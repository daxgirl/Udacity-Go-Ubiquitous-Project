#Advanced Android Sample App
Original app can be found here https://github.com/udacity/Advanced_Android_Development


#Sunshine Watchface for Android Wear

1. Works on both round and square watches. All dimensions are proportionate to scalable screen size. All dimensions are accordingly scaled in onSurfaceChanged is needed.
2. Syncs with weather adapter in mobile module on first draw. Later on syncs only if sync initiated by server or manually initiated on mobile node, or onVisibilityChanged if visible but NOT more than every 4 hours, or manually by tapping the weather view on the watchface.
3. Ambient mode accounts for low bit for anti aliasing
4. Option for automatic am/pm display

