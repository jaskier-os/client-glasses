package com.repository.glasses.listener.arstream;

import android.view.Surface;

/**
 * The UI-process end of the live AR stream's HUD layer.
 *
 * The compositor runs in `:backend` (that is where the camera and the GL code live) but the HUD
 * it must overlay is the `MainActivity` view hierarchy in the UI process. So `:backend` creates a
 * SurfaceTexture, and hands its Surface here for the UI to draw into.
 *
 * A Surface is Parcelable and survives a binder transaction; it does NOT reliably survive
 * `sendBroadcast`, where the system re-parcels extras and the native handle is not guaranteed.
 * That is why this is an AIDL callback and not another broadcast action.
 */
interface IHudSurfaceSink {
    /**
     * Start drawing the Activity's root view into `surface` at the given size, Choreographer-paced.
     * Replaces any previous surface.
     */
    oneway void setHudSurface(in Surface surface, int width, int height);

    /** Stop drawing and release the surface reference. */
    oneway void clearHudSurface();
}
