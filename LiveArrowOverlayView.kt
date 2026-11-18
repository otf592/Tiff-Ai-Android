package com.tiff.screenassistant.ui

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class LiveArrowOverlayView(context: Context) : View(context) {

    private var tipX: Float = -1f
    private var tipY: Float = -1f
    private var labelText: String = ""

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EC4899")
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB111318")
        style = Paint.Style.FILL
    }

    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EC4899")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun showPointer(xPercent: Float, yPercent: Float, label: String) {
        tipX = (xPercent / 100f) * width
        tipY = (yPercent / 100f) * height
        labelText = label
        visibility = VISIBLE
        invalidate()
    }

    fun hidePointer() {
        visibility = GONE
        tipX = -1f
        tipY = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tipX < 0 || tipY < 0) return

        val centerX = width / 2f
        val centerY = height / 2f
        val angle = atan2((tipY - centerY).toDouble(), (tipX - centerX).toDouble())

        val arrowLength = 150f
        val tailX = (tipX - arrowLength * cos(angle)).toFloat()
        val tailY = (tipY - arrowLength * sin(angle)).toFloat()

        val headLength = 52f
        val headAngle = Math.toRadians(28.0)
        val shaftHalfWidth = 14f
        val perpAngle = angle + Math.PI / 2.0

        val leftBarbX = (tipX - headLength * cos(angle - headAngle)).toFloat()
        val leftBarbY = (tipY - headLength * sin(angle - headAngle)).toFloat()
        val rightBarbX = (tipX - headLength * cos(angle + headAngle)).toFloat()
        val rightBarbY = (tipY - headLength * sin(angle + headAngle)).toFloat()

        val neckX = (tipX - (headLength * 0.72f) * cos(angle)).toFloat()
        val neckY = (tipY - (headLength * 0.72f) * sin(angle)).toFloat()

        val p1x = (neckX + shaftHalfWidth * cos(perpAngle)).toFloat()
        val p1y = (neckY + shaftHalfWidth * sin(perpAngle)).toFloat()
        val p2x = (tailX + shaftHalfWidth * cos(perpAngle)).toFloat()
        val p2y = (tailY + shaftHalfWidth * sin(perpAngle)).toFloat()
        val p3x = (tailX - shaftHalfWidth * cos(perpAngle)).toFloat()
        val p3y = (tailY - shaftHalfWidth * sin(perpAngle)).toFloat()
        val p4x = (neckX - shaftHalfWidth * cos(perpAngle)).toFloat()
        val p4y = (neckY - shaftHalfWidth * sin(perpAngle)).toFloat()

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftBarbX, leftBarbY)
            lineTo(p4x, p4y)
            lineTo(p3x, p3y)
            lineTo(p2x, p2y)
            lineTo(p1x, p1y)
            lineTo(rightBarbX, rightBarbY)
            close()
        }

        canvas.drawPath(path, arrowStrokePaint)
        canvas.drawPath(path, arrowFillPaint)

        if (labelText.isNotBlank()) {
            val textWidth = textPaint.measureText(labelText)
            val padH = 26f
            val padV = 18f
            val pillW = textWidth + padH * 2
            val pillH = textPaint.textSize + padV * 2

            val pillLeft = (tailX - pillW / 2f).coerceIn(20f, width - pillW - 20f)
            val pillTop = (tailY - pillH / 2f).coerceIn(80f, height - pillH - 80f)
            val rect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

            canvas.drawRoundRect(rect, 30f, 30f, pillBgPaint)
            canvas.drawRoundRect(rect, 30f, 30f, pillBorderPaint)
            canvas.drawText(labelText, pillLeft + padH, pillTop + padV + textPaint.textSize * 0.82f, textPaint)
        }
    }
}
