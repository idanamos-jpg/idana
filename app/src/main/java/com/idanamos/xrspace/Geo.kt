package com.idanamos.xrspace

import kotlin.math.cos
import kotlin.math.sin

/** Small geometry helpers. Conventions: OpenGL frame (x right, y up, -z forward), degrees for angles.
 *  Positive yaw turns to the left (rotation about +y), positive pitch looks up. */
object Geo {
    private fun rad(d: Float) = Math.toRadians(d.toDouble())

    /** Direction vector for Ry(yaw) * Rx(pitch) * (0,0,-1). */
    fun dirFromAngles(yawDeg: Float, pitchDeg: Float): FloatArray {
        val y = rad(yawDeg)
        val p = rad(pitchDeg)
        val cp = cos(p)
        return floatArrayOf((-cp * sin(y)).toFloat(), sin(p).toFloat(), (-cp * cos(y)).toFloat())
    }

    /** World -> window local: Rx(-pitch) * Ry(-yaw) * v */
    fun toLocal(v: FloatArray, yawDeg: Float, pitchDeg: Float): FloatArray {
        val a = rad(-yawDeg)
        val ca = cos(a)
        val sa = sin(a)
        val x1 = v[0] * ca + v[2] * sa
        val y1 = v[1].toDouble()
        val z1 = -v[0] * sa + v[2] * ca
        val b = rad(-pitchDeg)
        val cb = cos(b)
        val sb = sin(b)
        val y2 = y1 * cb - z1 * sb
        val z2 = y1 * sb + z1 * cb
        return floatArrayOf(x1.toFloat(), y2.toFloat(), z2.toFloat())
    }

    /** Möller–Trumbore ray/triangle from origin (0,0,0). Returns [t, b1, b2] or null. */
    fun rayTri(d: FloatArray, m: FloatArray, i0: Int, i1: Int, i2: Int): FloatArray? {
        val v0x = m[i0]; val v0y = m[i0 + 1]; val v0z = m[i0 + 2]
        val e1x = m[i1] - v0x; val e1y = m[i1 + 1] - v0y; val e1z = m[i1 + 2] - v0z
        val e2x = m[i2] - v0x; val e2y = m[i2 + 1] - v0y; val e2z = m[i2 + 2] - v0z
        val px = d[1] * e2z - d[2] * e2y
        val py = d[2] * e2x - d[0] * e2z
        val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (kotlin.math.abs(det) < 1e-9f) return null
        val inv = 1f / det
        val tx = -v0x; val ty = -v0y; val tz = -v0z
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0f || u > 1f) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
        if (v < 0f || u + v > 1f) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        if (t <= 0f) return null
        return floatArrayOf(t, u, v)
    }

    fun wrap180(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }
}
