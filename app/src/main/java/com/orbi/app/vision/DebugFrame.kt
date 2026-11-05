package com.orbi.app.vision

import android.graphics.PointF
import android.graphics.RectF

/**
 * Todo lo que el analizador de cámara calculó en un cuadro. Solo se publica en el modo creador
 * (nunca se guarda): sirve para ver en vivo qué está detectando Orbi y afinar los umbrales.
 *
 * Las coordenadas están en píxeles de la imagen analizada ya enderezada ([imageW] x [imageH]).
 */
data class DebugFrame(
    val imageW: Float,
    val imageH: Float,
    val faces: Int,
    val box: RectF?,
    val faceOval: List<PointF> = emptyList(),
    val lips: List<List<PointF>> = emptyList(),
    val eyes: List<List<PointF>> = emptyList(),
    val smile: Float? = null,
    val leftEye: Float? = null,
    val rightEye: Float? = null,
    val headX: Float = 0f,
    val headY: Float = 0f,
    val headZ: Float = 0f,
    val mouthRatio: Float? = null,
    val widthRatio: Float? = null,
    val light: Int = 0,
    val fps: Float = 0f,
    val analysisMs: Long = 0,
    /** 21 puntos por mano, en píxeles de la imagen. */
    val hands: List<List<PointF>> = emptyList(),
    /** Gesto de cada mano con su confianza ("Thumb_Up 0.91" o "–"). */
    val gestures: List<String> = emptyList(),
    /** Objetos y escenas del entorno con su confianza. */
    val labels: List<Pair<String, Float>> = emptyList(),
)
