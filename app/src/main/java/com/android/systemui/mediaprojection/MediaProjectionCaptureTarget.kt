package com.android.systemui.mediaprojection

import android.app.ActivityOptions.LaunchCookie
import android.os.Parcel
import android.os.Parcelable

/**
 * SystemUI MediaProjectionCaptureTarget IPC model for GameSpace single-app targeted recording.
 * Matches SystemUI's com.android.systemui.mediaprojection.MediaProjectionCaptureTarget parcel format.
 */
data class MediaProjectionCaptureTarget(val launchCookie: LaunchCookie?, val taskId: Int) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        LaunchCookie.writeToParcel(launchCookie, dest)
        dest.writeInt(taskId)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MediaProjectionCaptureTarget> {
        override fun createFromParcel(parcel: Parcel): MediaProjectionCaptureTarget {
            return MediaProjectionCaptureTarget(LaunchCookie.readFromParcel(parcel), parcel.readInt())
        }

        override fun newArray(size: Int): Array<MediaProjectionCaptureTarget?> {
            return arrayOfNulls(size)
        }
    }
}
