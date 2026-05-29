package com.earthwatch.face

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GlPrimitives {

    private var prog = 0
    val progDebug: Int get() = prog
    private var uMvp = 0; private var uColor = 0; private var aPos = 0
    private var progTex = 0
    private var uMvpTex = 0; private var uAlphaTex = 0; private var uTexTex = 0
    private var aPosTex = 0; private var aUvTex = 0
    private var initialized = false

    private var progSdf = 0
    private var uMvpSdf = 0; private var uColorSdf = 0; private var uP1Sdf = 0; private var uP2Sdf = 0; private var uP3Sdf = 0
    private var aPosSdf = 0

    private val lineBuf = allocFloat(12)
    private val fanBuf = allocFloat((362) * 2)
    private val arcBuf = allocFloat((129) * 4)
    private val ringBuf = allocFloat((129) * 4)
    private val quadPosBuf = allocFloat(8)
    private val quadUvBuf = allocFloat(8)
    private val sdfQuadBuf = allocFloat(8)

    private fun allocFloat(count: Int): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(count * 4)
        bb.order(ByteOrder.nativeOrder())
        return bb.asFloatBuffer()
    }

    private fun FloatBuffer.putArray(src: FloatArray, len: Int = src.size): FloatBuffer {
        position(0); limit(len); put(src, 0, len); position(0); return this
    }

    fun init() {
        if (prog != 0) { GLES20.glDeleteProgram(prog); prog = 0 }
        if (progTex != 0) { GLES20.glDeleteProgram(progTex); progTex = 0 }
        if (progSdf != 0) { GLES20.glDeleteProgram(progSdf); progSdf = 0 }
        initialized = true
        val vs = """attribute vec4 aPos;uniform mat4 uMVP;void main(){gl_Position=uMVP*aPos;}"""
        val fs = """precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}"""
        prog = mkProg(vs, fs)
        if (prog != 0) {
            uMvp = GLES20.glGetUniformLocation(prog, "uMVP")
            uColor = GLES20.glGetUniformLocation(prog, "uColor")
            aPos = GLES20.glGetAttribLocation(prog, "aPos")
        }
        val vst = """attribute vec4 aPos;attribute vec2 aUV;uniform mat4 uMVP;varying vec2 vUV;void main(){vUV=aUV;gl_Position=uMVP*aPos;}"""
        val fst = """precision mediump float;varying vec2 vUV;uniform sampler2D uTex;uniform float uAlpha;void main(){gl_FragColor=texture2D(uTex,vUV)*vec4(1.0,1.0,1.0,uAlpha);}"""
        progTex = mkProg(vst, fst)
        if (progTex != 0) {
            uMvpTex = GLES20.glGetUniformLocation(progTex, "uMVP")
            uAlphaTex = GLES20.glGetUniformLocation(progTex, "uAlpha")
            uTexTex = GLES20.glGetUniformLocation(progTex, "uTex")
            aPosTex = GLES20.glGetAttribLocation(progTex, "aPos")
            aUvTex = GLES20.glGetAttribLocation(progTex, "aUV")
        }

        val vsSdf = """attribute vec2 aPos;uniform mat4 uMVP;varying vec2 vPos;void main(){vPos=aPos;gl_Position=uMVP*vec4(aPos,0.0,1.0);}"""
        val fsSdf = """#extension GL_OES_standard_derivatives : enable
precision mediump float;varying vec2 vPos;uniform vec4 uColor;uniform vec4 uP1;uniform vec4 uP2;uniform vec4 uP3;
float sdRoundLine(vec2 p,vec2 a,vec2 b,float r){vec2 pa=p-a,ba=b-a;float h=clamp(dot(pa,ba)/dot(ba,ba),0.0,1.0);return length(pa-ba*h)-r;}
float sdArc(vec2 p,vec2 c,float r,float sa,float sw,float w){vec2 q=p-c;float dr=abs(length(q)-r)-w*0.5;float ang=atan(q.y,q.x);float da=mod(ang-sa+6.28318530,6.28318530);if(da<=sw)return dr;vec2 c1=c+r*vec2(cos(sa),sin(sa));vec2 c2=c+r*vec2(cos(sa+sw),sin(sa+sw));return min(length(p-c1),length(p-c2))-w*0.5;}
void main(){float d;if(uP3.w<0.5){d=sdRoundLine(vPos,uP1.xy,uP1.zw,uP2.x);}else{d=sdArc(vPos,uP1.xy,uP1.z,uP2.x,uP2.y,uP1.w);}float aa=fwidth(d);float a=1.0-smoothstep(-aa*0.5,aa*0.5,d);gl_FragColor=vec4(uColor.rgb,uColor.a*a);}"""
        progSdf = mkProg(vsSdf, fsSdf)
        if (progSdf != 0) {
            uMvpSdf = GLES20.glGetUniformLocation(progSdf, "uMVP")
            uColorSdf = GLES20.glGetUniformLocation(progSdf, "uColor")
            uP1Sdf = GLES20.glGetUniformLocation(progSdf, "uP1")
            uP2Sdf = GLES20.glGetUniformLocation(progSdf, "uP2")
            uP3Sdf = GLES20.glGetUniformLocation(progSdf, "uP3")
            aPosSdf = GLES20.glGetAttribLocation(progSdf, "aPos")
        }

        quadUvBuf.putArray(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    }

    fun release() {
        if (prog != 0) { GLES20.glDeleteProgram(prog); prog = 0 }
        if (progTex != 0) { GLES20.glDeleteProgram(progTex); progTex = 0 }
        if (progSdf != 0) { GLES20.glDeleteProgram(progSdf); progSdf = 0 }
        initialized = false
    }

    fun drawLine(mvp: FloatArray, x1: Float, y1: Float, x2: Float, y2: Float,
                 width: Float, color: FloatArray) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) return
        val nx = -dy / len * width / 2f; val ny = dx / len * width / 2f
        val d = floatArrayOf(
            x1 + nx, y1 + ny, x1 - nx, y1 - ny, x2 + nx, y2 + ny,
            x2 + nx, y2 + ny, x1 - nx, y1 - ny, x2 - nx, y2 - ny
        )
        lineBuf.putArray(d)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, lineBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun drawCircle(mvp: FloatArray, cx: Float, cy: Float, radius: Float, color: FloatArray) {
        val segments = 64
        val d = FloatArray((segments + 2) * 2)
        d[0] = cx; d[1] = cy
        for (i in 0..segments) {
            val a = 2f * kotlin.math.PI.toFloat() * i / segments
            d[(i + 1) * 2] = cx + radius * cos(a)
            d[(i + 1) * 2 + 1] = cy + radius * sin(a)
        }
        fanBuf.putArray(d)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, fanBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, segments + 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun drawRing(mvp: FloatArray, cx: Float, cy: Float, radius: Float, strokeWidth: Float, color: FloatArray) {
        val segments = 128
        val halfW = strokeWidth / 2f
        val innerR = radius - halfW; val outerR = radius + halfW
        val d = FloatArray((segments + 1) * 4)
        var idx = 0
        for (i in 0..segments) {
            val a = 2f * kotlin.math.PI.toFloat() * i / segments
            val cosA = cos(a); val sinA = sin(a)
            d[idx++] = cx + innerR * cosA; d[idx++] = cy + innerR * sinA
            d[idx++] = cx + outerR * cosA; d[idx++] = cy + outerR * sinA
        }
        ringBuf.putArray(d, idx)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, ringBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (segments + 1) * 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun drawArc(mvp: FloatArray, cx: Float, cy: Float, radius: Float,
                startAngle: Float, sweepAngle: Float, width: Float, color: FloatArray) {
        val segments = kotlin.math.max(8, (kotlin.math.abs(sweepAngle) / (2f * kotlin.math.PI.toFloat()) * 128).toInt())
        val halfW = width / 2f
        val innerR = radius - halfW; val outerR = radius + halfW
        val d = FloatArray((segments + 1) * 4)
        var idx = 0
        for (i in 0..segments) {
            val a = startAngle + sweepAngle * i / segments
            val cosA = cos(a); val sinA = sin(a)
            d[idx++] = cx + innerR * cosA; d[idx++] = cy + innerR * sinA
            d[idx++] = cx + outerR * cosA; d[idx++] = cy + outerR * sinA
        }
        arcBuf.putArray(d, idx)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, arcBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (segments + 1) * 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun drawArcRounded(mvp: FloatArray, cx: Float, cy: Float, radius: Float,
                        startAngle: Float, sweepAngle: Float, width: Float, color: FloatArray) {
        drawArc(mvp, cx, cy, radius, startAngle, sweepAngle, width, color)
        val capR = width / 2f
        val startA = startAngle
        val endA = startAngle + sweepAngle
        drawCircle(mvp, cx + radius * cos(startA), cy + radius * sin(startA), capR, color)
        drawCircle(mvp, cx + radius * cos(endA), cy + radius * sin(endA), capR, color)
    }

    fun drawSdfRoundLine(mvp: FloatArray, x1: Float, y1: Float, x2: Float, y2: Float,
                          width: Float, color: FloatArray) {
        if (progSdf == 0) { drawLine(mvp, x1, y1, x2, y2, width, color); return }
        val pad = width * 0.5f + 2f
        val minX = minOf(x1, x2) - pad; val minY = minOf(y1, y2) - pad
        val maxX = maxOf(x1, x2) + pad; val maxY = maxOf(y1, y2) + pad
        val d = floatArrayOf(minX, maxY, maxX, maxY, minX, minY, maxX, minY)
        sdfQuadBuf.putArray(d)

        GLES20.glUseProgram(progSdf)
        GLES20.glUniformMatrix4fv(uMvpSdf, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColorSdf, 1, color, 0)
        GLES20.glUniform4f(uP1Sdf, x1, y1, x2, y2)
        GLES20.glUniform4f(uP2Sdf, width * 0.5f, 0f, 0f, 0f)
        GLES20.glUniform4f(uP3Sdf, 0f, 0f, 0f, 0f)
        GLES20.glEnableVertexAttribArray(aPosSdf)
        GLES20.glVertexAttribPointer(aPosSdf, 2, GLES20.GL_FLOAT, false, 0, sdfQuadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosSdf)
    }

    fun drawSdfArc(mvp: FloatArray, cx: Float, cy: Float, radius: Float,
                    startAngle: Float, sweepAngle: Float, width: Float, color: FloatArray) {
        if (progSdf == 0) { drawArcRounded(mvp, cx, cy, radius, startAngle, sweepAngle, width, color); return }
        val pad = width * 0.5f + 2f
        val outerR = radius + pad
        val d = floatArrayOf(cx - outerR, cy + outerR, cx + outerR, cy + outerR, cx - outerR, cy - outerR, cx + outerR, cy - outerR)
        sdfQuadBuf.putArray(d)

        GLES20.glUseProgram(progSdf)
        GLES20.glUniformMatrix4fv(uMvpSdf, 1, false, mvp, 0)
        GLES20.glUniform4fv(uColorSdf, 1, color, 0)
        GLES20.glUniform4f(uP1Sdf, cx, cy, radius, width)
        GLES20.glUniform4f(uP2Sdf, startAngle, sweepAngle, 0f, 0f)
        GLES20.glUniform4f(uP3Sdf, 0f, 0f, 0f, 1f)
        GLES20.glEnableVertexAttribArray(aPosSdf)
        GLES20.glVertexAttribPointer(aPosSdf, 2, GLES20.GL_FLOAT, false, 0, sdfQuadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosSdf)
    }

    fun drawTexturedQuad(mvp: FloatArray, texId: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        drawTexturedQuadInternal(mvp, texId, x, y, w, h, alpha, quadUvBuf)
    }

    private fun drawTexturedQuadInternal(mvp: FloatArray, texId: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float, uvBuf: FloatBuffer) {
        val d = floatArrayOf(x, y + h, x + w, y + h, x, y, x + w, y)
        quadPosBuf.putArray(d)

        GLES20.glUseProgram(progTex)
        GLES20.glUniformMatrix4fv(uMvpTex, 1, false, mvp, 0)
        GLES20.glUniform1f(uAlphaTex, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexTex, 0)

        GLES20.glEnableVertexAttribArray(aPosTex)
        GLES20.glVertexAttribPointer(aPosTex, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)
        GLES20.glEnableVertexAttribArray(aUvTex)
        GLES20.glVertexAttribPointer(aUvTex, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosTex)
        GLES20.glDisableVertexAttribArray(aUvTex)
    }

    private fun mkProg(v: String, f: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, v); val fs = compile(GLES20.GL_FRAGMENT_SHADER, f)
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val st = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) { GLES20.glDeleteProgram(p); return 0 }
        return p
    }

    private fun compile(t: Int, s: String): Int {
        val sh = GLES20.glCreateShader(t); GLES20.glShaderSource(sh, s); GLES20.glCompileShader(sh)
        val st = IntArray(1); GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) { android.util.Log.e("GlPrimitives", "Shader compile error: ${GLES20.glGetShaderInfoLog(sh)}"); GLES20.glDeleteShader(sh); return 0 }
        return sh
    }
}
