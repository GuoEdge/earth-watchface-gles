package com.earthwatch.face

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

class GlOverlay {

    private var cloudTexId = 0
    var cloudW = 0; private set
    private var cloudH = 0
    var cloudInited = false; private set

    private var atmoTexId = 0

    private val quadPosBuf = allocFloat(8)
    private val quadUvBuf = allocFloat(8)

    private var progTex = 0
    private var uMvpTex = 0; private var uAlphaTex = 0; private var uOffUTex = 0; private var uTexTex = 0
    private var aPosTex = 0; private var aUvTex = 0

    private var progCloud = 0
    private var uMvpCloud = 0; private var uAlphaCloud = 0; private var uOffUCloud = 0; private var uTexCloud = 0
    private var uCenterCloud = 0; private var uRadiusCloud = 0
    private var uRotYCloud = 0
    private var aPosCloud = 0; private var aUvCloud = 0

    private fun allocFloat(count: Int): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(count * 4)
        bb.order(ByteOrder.nativeOrder())
        return bb.asFloatBuffer()
    }

    private fun FloatBuffer.putArray(src: FloatArray, len: Int = src.size): FloatBuffer {
        position(0); limit(len); put(src, 0, len); position(0); return this
    }

    fun init() {
        if (progTex != 0) { GLES20.glDeleteProgram(progTex); progTex = 0 }
        if (progCloud != 0) { GLES20.glDeleteProgram(progCloud); progCloud = 0 }
        val vst = """attribute vec2 aPos;attribute vec2 aUV;uniform mat4 uMVP;varying vec2 vUV;void main(){vUV=aUV;gl_Position=uMVP*vec4(aPos,0.0,1.0);}"""
        val fst = """precision mediump float;varying vec2 vUV;uniform sampler2D uTex;uniform float uAlpha;uniform float uOffU;void main(){vec2 uv=vec2(vUV.x+uOffU,vUV.y);gl_FragColor=texture2D(uTex,uv)*vec4(1.0,1.0,1.0,uAlpha);}"""
        progTex = mkProg(vst, fst)
        if (progTex != 0) {
            uMvpTex = GLES20.glGetUniformLocation(progTex, "uMVP")
            uAlphaTex = GLES20.glGetUniformLocation(progTex, "uAlpha")
            uOffUTex = GLES20.glGetUniformLocation(progTex, "uOffU")
            uTexTex = GLES20.glGetUniformLocation(progTex, "uTex")
            aPosTex = GLES20.glGetAttribLocation(progTex, "aPos")
            aUvTex = GLES20.glGetAttribLocation(progTex, "aUV")
        }

        val vsc = """attribute vec2 aPos;attribute vec2 aUV;uniform mat4 uMVP;varying vec2 vUV;varying vec2 vPos;void main(){vUV=aUV;vPos=aPos;gl_Position=uMVP*vec4(aPos,0.0,1.0);}"""
        val fsc = """precision mediump float;varying vec2 vUV;varying vec2 vPos;uniform sampler2D uTex;uniform float uAlpha;uniform float uOffU;uniform vec2 uCenter;uniform float uRadius;uniform float uRotY;void main(){vec2 off=vPos-uCenter;float d=length(off)/uRadius;if(d>1.0)discard;float edge=smoothstep(1.0,0.92,d);float nx=off.x/uRadius;float ny=off.y/uRadius;float z=sqrt(1.0-d*d);float cy=cos(uRotY);float sy=sin(uRotY);float rx=nx*cy+z*sy;float rz=-nx*sy+z*cy;float cx2=cos(-0.13962634);float sx2=sin(-0.13962634);float ry2=ny*cx2-rz*sx2;float rz2=ny*sx2+rz*cx2;float lon=atan(rx,rz2);float lat=asin(ry2);vec2 uv=vec2(lon/6.28318530+0.5+uOffU,lat/3.14159265+0.5);vec4 tc=texture2D(uTex,uv);gl_FragColor=tc*vec4(1.0,1.0,1.0,uAlpha*edge);}"""
        progCloud = mkProg(vsc, fsc)
        if (progCloud != 0) {
            uMvpCloud = GLES20.glGetUniformLocation(progCloud, "uMVP")
            uAlphaCloud = GLES20.glGetUniformLocation(progCloud, "uAlpha")
            uOffUCloud = GLES20.glGetUniformLocation(progCloud, "uOffU")
            uTexCloud = GLES20.glGetUniformLocation(progCloud, "uTex")
            uCenterCloud = GLES20.glGetUniformLocation(progCloud, "uCenter")
            uRadiusCloud = GLES20.glGetUniformLocation(progCloud, "uRadius")
            uRotYCloud = GLES20.glGetUniformLocation(progCloud, "uRotY")
            aPosCloud = GLES20.glGetAttribLocation(progCloud, "aPos")
            aUvCloud = GLES20.glGetAttribLocation(progCloud, "aUV")
        }

        quadUvBuf.putArray(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    }

    fun initClouds(bmp: Bitmap) {
        if (cloudTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(cloudTexId), 0)
        cloudW = bmp.width; cloudH = bmp.height
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); cloudTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cloudTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        cloudInited = true
    }

    fun uploadAtmosphere(bmp: Bitmap) {
        if (atmoTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(atmoTexId), 0)
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); atmoTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atmoTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun drawTexturedQuad(mvp: FloatArray, texId: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float, uOffset: Float = 0f) {
        val d = floatArrayOf(x, y + h, x + w, y + h, x, y, x + w, y)
        quadPosBuf.putArray(d)

        GLES20.glUseProgram(progTex)
        GLES20.glUniformMatrix4fv(uMvpTex, 1, false, mvp, 0)
        GLES20.glUniform1f(uAlphaTex, alpha)
        GLES20.glUniform1f(uOffUTex, uOffset)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexTex, 0)
        GLES20.glEnableVertexAttribArray(aPosTex)
        GLES20.glVertexAttribPointer(aPosTex, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)
        GLES20.glEnableVertexAttribArray(aUvTex)
        GLES20.glVertexAttribPointer(aUvTex, 2, GLES20.GL_FLOAT, false, 0, quadUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosTex)
        GLES20.glDisableVertexAttribArray(aUvTex)
    }

    fun drawClouds(mvp: FloatArray, cx: Float, cy: Float, radius: Float, uOffset: Float, alpha: Float, rotY: Float) {
        if (!cloudInited || cloudTexId == 0 || progCloud == 0) return
        val x = cx - radius; val y = cy - radius; val s = radius * 2
        val d = floatArrayOf(x, y + s, x + s, y + s, x, y, x + s, y)
        quadPosBuf.putArray(d)

        GLES20.glUseProgram(progCloud)
        GLES20.glUniformMatrix4fv(uMvpCloud, 1, false, mvp, 0)
        GLES20.glUniform1f(uAlphaCloud, alpha)
        GLES20.glUniform1f(uOffUCloud, uOffset)
        GLES20.glUniform2f(uCenterCloud, cx, cy)
        GLES20.glUniform1f(uRadiusCloud, radius)
        GLES20.glUniform1f(uRotYCloud, rotY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cloudTexId)
        GLES20.glUniform1i(uTexCloud, 0)
        GLES20.glEnableVertexAttribArray(aPosCloud)
        GLES20.glVertexAttribPointer(aPosCloud, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)
        GLES20.glEnableVertexAttribArray(aUvCloud)
        GLES20.glVertexAttribPointer(aUvCloud, 2, GLES20.GL_FLOAT, false, 0, quadUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosCloud)
        GLES20.glDisableVertexAttribArray(aUvCloud)
    }

    fun drawAtmosphere(mvp: FloatArray, cx: Float, cy: Float, radius: Float, alpha: Float) {
        if (atmoTexId == 0) return
        drawTexturedQuad(mvp, atmoTexId, cx - radius, cy - radius, radius * 2, radius * 2, alpha)
    }

    fun drawNightOverlay(mvp: FloatArray, cx: Float, cy: Float, radius: Float, texId: Int, rotY: Float) {
        if (texId == 0 || progCloud == 0) return
        val x = cx - radius; val y = cy - radius; val s = radius * 2
        val d = floatArrayOf(x, y + s, x + s, y + s, x, y, x + s, y)
        quadPosBuf.putArray(d)

        GLES20.glUseProgram(progCloud)
        GLES20.glUniformMatrix4fv(uMvpCloud, 1, false, mvp, 0)
        GLES20.glUniform1f(uAlphaCloud, 1f)
        GLES20.glUniform1f(uOffUCloud, 0f)
        GLES20.glUniform2f(uCenterCloud, cx, cy)
        GLES20.glUniform1f(uRadiusCloud, radius)
        GLES20.glUniform1f(uRotYCloud, rotY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexCloud, 0)
        GLES20.glEnableVertexAttribArray(aPosCloud)
        GLES20.glVertexAttribPointer(aPosCloud, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)
        GLES20.glEnableVertexAttribArray(aUvCloud)
        GLES20.glVertexAttribPointer(aUvCloud, 2, GLES20.GL_FLOAT, false, 0, quadUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosCloud)
        GLES20.glDisableVertexAttribArray(aUvCloud)
    }

    fun release() {
        if (progTex != 0) { GLES20.glDeleteProgram(progTex); progTex = 0 }
        if (progCloud != 0) { GLES20.glDeleteProgram(progCloud); progCloud = 0 }
        if (cloudTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(cloudTexId), 0); cloudTexId = 0 }
        if (atmoTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(atmoTexId), 0); atmoTexId = 0 }
        cloudInited = false
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
        if (st[0] == 0) { android.util.Log.e("GlOverlay", "Shader compile error: ${GLES20.glGetShaderInfoLog(sh)}"); GLES20.glDeleteShader(sh); return 0 }
        return sh
    }
}
