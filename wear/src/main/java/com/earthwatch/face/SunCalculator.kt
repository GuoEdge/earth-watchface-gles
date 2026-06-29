package com.earthwatch.face

import java.time.*
import kotlin.math.*

class SunCalculator {
    private val out = FloatArray(3)

    fun sunDirection(dateTime: LocalDateTime, dest: FloatArray = out): FloatArray {
        val bh = dateTime.hour.toFloat() + dateTime.minute / 60f + dateTime.second / 3600f
        val ha = -(bh / 12f) * PI.toFloat()
        val doy = dateTime.dayOfYear.toFloat()
        val dec = (-23.44f * cos((2f * PI.toFloat() / 365f) * (doy + 10f)) * (PI.toFloat() / 180f))
        val sx = cos(dec) * cos(ha)
        val sy = sin(dec)
        val sz = cos(dec) * sin(ha)
        val len = sqrt(sx * sx + sy * sy + sz * sz)
        dest[0] = sx / len
        dest[1] = sy / len
        dest[2] = sz / len
        return dest
    }
}
