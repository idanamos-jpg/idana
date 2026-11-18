package com.idanamos.xrdesk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Small picture on the phone: the glasses' field of view and where the virtual monitor sits in it. */
class PreviewView(ctx: Context) : View(ctx) {

    var dispW = 1920
    var dispH = 1080

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(150, 160, 180, 210)
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 200, 210, 230); textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 9f / 16f * 0.85f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = 10f
        val aw = width - pad * 2
        val ah = height - pad * 2
        val s = minOf(aw / dispW, ah / dispH)
        val fw = dispW * s
        val fh = dispH * s
        val fl = (width - fw) / 2f
        val ft = (height - fh) / 2f
        p.color = Color.rgb(6, 8, 12)
        c.drawRoundRect(fl, ft, fl + fw, ft + fh, 14f, 14f, p)
        c.drawRoundRect(fl, ft, fl + fw, ft + fh, 14f, 14f, frame)

        val g = Desk.geometry(dispW, dispH)
        val r = RectF(fl + g.rect.left * s, ft + g.rect.top * s, fl + g.rect.right * s, ft + g.rect.bottom * s)
        p.color = if (g.clipped) Color.rgb(150, 90, 40) else Color.rgb(40, 90, 170)
        c.drawRoundRect(r, 6f, 6f, p)
        val tb = Desk.taskbarH(g) * s
        p.color = Color.argb(200, 10, 12, 20)
        c.drawRect(r.left, r.bottom - tb, r.right, r.bottom, p)
        txt.textSize = (r.height() * 0.16f).coerceIn(18f, 40f)
        txt.color = Color.WHITE
        c.drawText(Desk.summary(), r.centerX(), r.centerY() + txt.textSize * 0.2f, txt)
        txt.textSize = 22f
        txt.color = Color.argb(150, 200, 210, 230)
        c.drawText("שדה הראייה של המשקפיים", width / 2f, ft + 26f, txt)
    }
}
