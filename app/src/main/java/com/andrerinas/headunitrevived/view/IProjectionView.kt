package com.andrerinas.headunitrevived.view

import android.view.Surface

interface IProjectionView {
    interface Callbacks {
        fun onSurfaceCreated(surface: Surface)
        fun onSurfaceDestroyed(surface: Surface)
        fun onSurfaceChanged(surface: Surface, width: Int, height: Int)
    }

    fun addCallback(callback: Callbacks)
    fun removeCallback(callback: Callbacks)
    fun setVideoSize(width: Int, height: Int)
    fun setVideoScale(scaleX: Float, scaleY: Float)
}
