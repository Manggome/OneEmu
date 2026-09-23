package com.manggome.oneemu.emu.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.manggome.oneemu.emu.NativeBridge

/**
 * The phone's accelerometer and gyroscope, fed to the core through the libretro sensor interface.
 *
 * Only Dolphin asks for them, and it reads them as a Wii Remote's own motion. So each reading is turned
 * twice before it goes down:
 *
 * 1. from the phone's natural axes to the screen as it is shown (x right, y up, z out of the glass),
 *    because Android reports sensors in portrait terms whichever way the phone is held;
 * 2. from the screen to the remote Dolphin expects: +X to its left, +Y back towards the player, +Z out
 *    of its button face. A phone held facing the player is a remote lying flat and aimed at the screen,
 *    so remote X = -screen x, remote Y = screen z, remote Z = screen y. (A proper rotation, so angular
 *    velocity turns the same way as acceleration.)
 *
 * The upshot is the mapping a player expects: turn the phone right and the pointer goes right, tip its
 * far edge up and the pointer goes up.
 *
 * Units follow libretro: acceleration in g, angular velocity in rad/s.
 */
class MotionSensors(context: Context, private val rotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var running = false

    val hasAccelerometer: Boolean get() = accelerometer != null
    val hasGyroscope: Boolean get() = gyroscope != null

    fun start() {
        if (running) return
        running = true
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        if (!running) return
        running = false
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> KIND_ACCEL
            Sensor.TYPE_GYROSCOPE -> KIND_GYRO
            else -> return
        }
        val scale = if (kind == KIND_ACCEL) 1f / SensorManager.GRAVITY_EARTH else 1f
        val r = toRemote(event.values[0], event.values[1], event.values[2], rotation())
        NativeBridge.setSensor(kind, r[0] * scale, r[1] * scale, r[2] * scale)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val KIND_ACCEL = 0
        const val KIND_GYRO = 1

        /** Device axes (portrait natural orientation) to the Wii Remote frame, for [displayRotation]. */
        fun toRemote(x: Float, y: Float, z: Float, displayRotation: Int): FloatArray {
            // Screen axes: the same remap Android's own AccelerometerPlay sample uses.
            val (sx, sy) = when (displayRotation) {
                Surface.ROTATION_90 -> -y to x
                Surface.ROTATION_180 -> -x to -y
                Surface.ROTATION_270 -> y to -x
                else -> x to y
            }
            return floatArrayOf(-sx, z, sy)
        }
    }
}
