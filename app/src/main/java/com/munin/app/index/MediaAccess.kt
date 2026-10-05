package com.munin.app.index

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccessState { FULL, PARTIAL, NONE }

object MediaAccess {
    /** Permissions to request for photo access on this Android version. */
    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 14+ lets the user share only some photos; then only those are visible to MediaStore. */
    fun state(context: Context): MediaAccessState {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccessState.FULL
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccessState.PARTIAL
                else -> MediaAccessState.NONE
            }
            Build.VERSION.SDK_INT >= 33 -> if (granted(Manifest.permission.READ_MEDIA_IMAGES)) MediaAccessState.FULL else MediaAccessState.NONE
            else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccessState.FULL else MediaAccessState.NONE
        }
    }
}
