package com.earthwatch.face

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class EarthGles {

    private var size = 0
    private var dayBmp: Bitmap? = null
    private var nightBmp: Bitmap? = null

    private var progEarth = 0
    val progEarthDebug: Int get() = progEarth
    private var vbo = 0
    private var ibo = 0
    private var idxCnt = 0
    private var tDay = 0
    private var tNight = 0
    private var umvp = 0; private var umod = 0; private var usun = 0
    private var uTexDay = 0; private var uTexNight = 0
    private var ap = 0; private var an = 0; private var auv = 0

    fun init(s: Int, day: Bitmap?, night: Bitmap?) {
        dayBmp = day; nightBmp = night
        if (s == size && progEarth != 0) { mkTex(); return }
        release(); size = s
        mkShaders()
        if (progEarth == 0) return
        mkSphere()
        mkTex()
    }

    private fun mkShaders() {
        val ve = """uniform mat4 uMVPMatrix;uniform mat4 uModelMatrix;attribute vec4 aPosition;attribute vec3 aNormal;attribute vec2 aTexCoord;varying vec3 vNormal;varying vec3 vWorldPos;varying vec2 vTexCoord;void main(){vNormal=normalize(mat3(uModelMatrix)*aNormal);vec4 wp=uModelMatrix*aPosition;vWorldPos=wp.xyz;vTexCoord=aTexCoord;gl_Position=uMVPMatrix*aPosition;}"""
        val fe = """precision mediump float;varying vec3 vNormal;varying vec3 vWorldPos;varying vec2 vTexCoord;uniform vec3 uSunDir;uniform sampler2D uTexDay;uniform sampler2D uTexNight;void main(){float NdotL=dot(normalize(vNormal),normalize(uSunDir));float t=smoothstep(-0.18,0.10,NdotL);vec4 dc=texture2D(uTexDay,vTexCoord);vec4 nc=texture2D(uTexNight,vTexCoord);vec3 dayLit=dc.rgb*(0.85+t*0.15);vec3 bc=mix(nc.rgb,dayLit,t);vec3 viewDir=normalize(vec3(0.0,0.0,5.6)-vWorldPos);float sp=pow(max(dot(reflect(-normalize(uSunDir),normalize(vNormal)),viewDir),0.0),30.0)*0.65*t;gl_FragColor=vec4(bc+sp,1.0);}"""
        progEarth = mkProg(ve, fe)
        if (progEarth == 0) {
            android.util.Log.e("EarthGles", "Shader program creation failed")
            return
        }
        umvp = GLES20.glGetUniformLocation(progEarth, "uMVPMatrix")
        umod = GLES20.glGetUniformLocation(progEarth, "uModelMatrix")
        usun = GLES20.glGetUniformLocation(progEarth, "uSunDir")
        uTexDay = GLES20.glGetUniformLocation(progEarth, "uTexDay")
        uTexNight = GLES20.glGetUniformLocation(progEarth, "uTexNight")
        ap = GLES20.glGetAttribLocation(progEarth, "aPosition")
        an = GLES20.glGetAttribLocation(progEarth, "aNormal")
        auv = GLES20.glGetAttribLocation(progEarth, "aTexCoord")
    }

    private fun mkProg(v: String, f: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, v); val fs = compile(GLES20.GL_FRAGMENT_SHADER, f)
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val status = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            android.util.Log.e("EarthGles", "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p); return 0
        }
        return p
    }

    private fun compile(t: Int, s: String): Int {
        val sh = GLES20.glCreateShader(t); GLES20.glShaderSource(sh, s); GLES20.glCompileShader(sh)
        val status = IntArray(1); GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            android.util.Log.e("EarthGles", "Shader compile error: ${GLES20.glGetShaderInfoLog(sh)}")
            GLES20.glDeleteShader(sh); return 0
        }
        return sh
    }

    private fun mkSphere() {
        val st = 80; val sl = 192
        val verts = FloatArray((st + 1) * (sl + 1) * 8)
        val idxs = ShortArray(st * sl * 6)
        var vi = 0
        for (i in 0..st) { val phi = PI * i / st; for (j in 0..sl) { val th = 2.0 * PI * j / sl; val x = (sin(phi) * cos(th)).toFloat(); val y = cos(phi).toFloat(); val z = (sin(phi) * sin(th)).toFloat(); verts[vi++] = x; verts[vi++] = y; verts[vi++] = z; verts[vi++] = x; verts[vi++] = y; verts[vi++] = z; verts[vi++] = (j.toFloat() / sl); verts[vi++] = (i.toFloat() / st) } }
        var ii = 0
        for (i in 0 until st) { for (j in 0 until sl) { val a = (i * (sl + 1) + j).toShort(); val b = (a + sl + 1).toShort(); idxs[ii++] = a; idxs[ii++] = b; idxs[ii++] = (a + 1).toShort(); idxs[ii++] = (a + 1).toShort(); idxs[ii++] = b; idxs[ii++] = (b + 1).toShort() } }
        idxCnt = idxs.size
        val bs = IntArray(1); GLES20.glGenBuffers(1, bs, 0); vbo = bs[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val vbb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        vbb.asFloatBuffer().put(verts).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vbb, GLES20.GL_STATIC_DRAW)
        val bi = IntArray(1); GLES20.glGenBuffers(1, bi, 0); ibo = bi[0]
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val ibb = ByteBuffer.allocateDirect(idxs.size * 2).order(ByteOrder.nativeOrder())
        ibb.asShortBuffer().put(idxs).position(0)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idxs.size * 2, ibb, GLES20.GL_STATIC_DRAW)
    }

    private fun mkTex() {
        if (tDay != 0) { val ids = intArrayOf(tDay, tNight); GLES20.glDeleteTextures(2, ids, 0) }
        tDay = dayBmp?.let { texFrom(it) } ?: 0
        tNight = nightBmp?.let { texFrom(it) } ?: 0
    }

    private fun texFrom(b: Bitmap): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D); return ids[0]
    }

    fun render(s: Int, ry: Float, sunDir: FloatArray, vpX: Int = 0, vpY: Int = 0) {
        if (progEarth == 0) return

        GLES20.glViewport(vpX, vpY, s, s)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        val proj = FloatArray(16); android.opengl.Matrix.perspectiveM(proj, 0, 26f, 1f, 0.1f, 100f)
        val view = FloatArray(16); android.opengl.Matrix.setLookAtM(view, 0, 0f, 0f, 5.6f, 0f, 0f, 0f, 0f, 1f, 0f)
        val vp = FloatArray(16); android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val mod = FloatArray(16); android.opengl.Matrix.setIdentityM(mod, 0)
        android.opengl.Matrix.rotateM(mod, 0, ry, 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(mod, 0, -8.0f, 1f, 0f, 0f)
        android.opengl.Matrix.scaleM(mod, 0, 1.1f, 1.1f, 1.1f)
        val mvp = FloatArray(16); android.opengl.Matrix.multiplyMM(mvp, 0, vp, 0, mod, 0)
        val slo = FloatArray(3)
        slo[0] = mod[0]*sunDir[0] + mod[4]*sunDir[1] + mod[8]*sunDir[2]
        slo[1] = mod[1]*sunDir[0] + mod[5]*sunDir[1] + mod[9]*sunDir[2]
        slo[2] = mod[2]*sunDir[0] + mod[6]*sunDir[1] + mod[10]*sunDir[2]
        val slen = sqrt(slo[0]*slo[0]+slo[1]*slo[1]+slo[2]*slo[2])
        if (slen > 0f) { slo[0] /= slen; slo[1] /= slen; slo[2] /= slen }

        GLES20.glUseProgram(progEarth)
        GLES20.glUniformMatrix4fv(umvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(umod, 1, false, mod, 0)
        GLES20.glUniform3f(usun, slo[0], slo[1], slo[2])

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tDay)
        GLES20.glUniform1i(uTexDay, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tNight)
        GLES20.glUniform1i(uTexNight, 1)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(ap); GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(an); GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glEnableVertexAttribArray(auv); GLES20.glVertexAttribPointer(auv, 2, GLES20.GL_FLOAT, false, 32, 24)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCnt, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(ap)
        GLES20.glDisableVertexAttribArray(an)
        GLES20.glDisableVertexAttribArray(auv)
    }

    fun release() {
        if (progEarth != 0) { GLES20.glDeleteProgram(progEarth); progEarth = 0 }
        if (vbo != 0) { GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0); vbo = 0 }
        if (ibo != 0) { GLES20.glDeleteBuffers(1, intArrayOf(ibo), 0); ibo = 0 }
        if (tDay != 0 || tNight != 0) { GLES20.glDeleteTextures(2, intArrayOf(tDay, tNight), 0); tDay = 0; tNight = 0 }
        idxCnt = 0
    }
}
