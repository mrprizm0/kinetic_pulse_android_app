package com.kineticlabs.pulse.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.kineticlabs.pulse.ble.TrickKind
import com.kineticlabs.pulse.ble.landingScore
import com.kineticlabs.pulse.data.WheelieRecord
import com.kineticlabs.pulse.ride.SavedRide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Share cards: a trick or a ride drawn as a 1080 x 1350 Metro image (the
 * size Instagram, TikTok and X all like), handed to Android's share sheet.
 * Also exports rides as GPX for Strava and friends.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    private val BG = Color.BLACK
    private val TILE = Color.rgb(30, 30, 35)
    private val TEXT = Color.rgb(245, 245, 247)
    private val MUTED = Color.rgb(122, 122, 132)
    private val ORANGE = Color.rgb(255, 138, 31)
    private val GOLD = Color.rgb(245, 200, 75)
    private val T_GREEN = Color.rgb(0, 138, 40)
    private val T_AMBER = Color.rgb(205, 125, 0)
    private val T_RED = Color.rgb(176, 16, 48)
    private val T_BLUE = Color.rgb(0, 80, 220)

    private val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private fun paint(color: Int, size: Float, face: Typeface = light) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = face
    }

    private fun score(s: Int) = when { s >= 80 -> T_GREEN; s >= 50 -> T_AMBER; else -> T_RED }

    private fun fmtSecs(ms: Long): String {
        val t = ms / 100; val s = t / 10
        return if (s >= 60) String.format(Locale.US, "%d:%02d.%ds", s / 60, s % 60, t % 10)
        else String.format(Locale.US, "%d.%ds", s, t % 10)
    }

    /** Flat tile: big value top-left, small lowercase label bottom-left. */
    private fun tile(c: Canvas, x: Float, y: Float, w: Float, h: Float, bg: Int, value: String, label: String, dark: Boolean = false) {
        c.drawRect(x, y, x + w, y + h, Paint().apply { color = bg })
        val fg = if (dark) BG else TEXT
        var size = 92f
        val p = paint(fg, size)
        while (p.measureText(value) > w - 52 && size > 40) { size -= 4; p.textSize = size }
        c.drawText(value, x + 26, y + 26 + size * .78f, p)
        c.drawText(label, x + 26, y + h - 30, paint(if (dark) Color.argb(170, 0, 0, 0) else Color.argb(190, 255, 255, 255), 34f))
    }

    private fun footer(c: Canvas) {
        c.drawText("pulse", 80f, H - 72f, paint(TEXT, 64f, thin))
        val p = paint(ORANGE, 34f)
        c.drawText("by kinetic labs", 80f + paint(TEXT, 64f, thin).measureText("pulse") + 22f, H - 76f, p)
        val d = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        val m = paint(MUTED, 32f); m.textAlign = Paint.Align.RIGHT
        c.drawText(d, W - 80f, H - 76f, m)
    }

    private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val g = Paint().apply {
            shader = RadialGradient(cx, cy, r, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, g)
    }

    /** A wheelie, stoppie or wheelie circle. */
    fun trickBitmap(r: WheelieRecord): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val stoppie = r.kind == TrickKind.STOPPIE
        val circle = r.kind == TrickKind.CIRCLE
        val tint = when { r.newBest -> GOLD; stoppie -> Color.rgb(255, 77, 94); circle -> GOLD; else -> Color.rgb(46, 224, 122) }
        glow(c, W / 2f, 470f, 620f, tint)

        val title = when {
            r.newBest && stoppie -> "BEST STOPPIE"
            r.newBest -> "NEW BEST"
            stoppie -> "stoppie"
            circle -> String.format(Locale.US, "wheelie circle · %.1fx", abs(r.turns))
            else -> "wheelie"
        }
        c.drawText(title, 80f, 170f, paint(tint, 64f, if (r.newBest) Typeface.DEFAULT else light))
        c.drawText(fmtSecs(r.ms), 64f, 480f, paint(TEXT, 300f, thin))
        c.drawText(if (stoppie) "nose down" else "on the back wheel", 84f, 560f, paint(MUTED, 40f))

        val gap = 14f; val tw = (W - 160f - 2 * gap) / 3; val th = 230f; val y1 = 680f
        tile(c, 80f, y1, tw, th, TILE, "${r.peak.roundToInt()}°", if (stoppie) "depth" else "peak angle")
        tile(c, 80f + tw + gap, y1, tw, th, score(r.balance), "${r.balance}", "balance")
        if (stoppie) {
            tile(c, 80f + 2 * (tw + gap), y1, tw, th, if (r.newBest) GOLD else TILE, if (r.newBest) "best" else "—", "vs best", r.newBest)
        } else {
            val land = landingScore(r.landingG)
            tile(c, 80f + 2 * (tw + gap), y1, tw, th, if (r.landingG > 0) score(land) else TILE,
                if (r.landingG > 0) "$land" else "—", "landing")
        }
        val y2 = y1 + th + gap
        if (!stoppie) {
            tile(c, 80f, y2, tw, th, TILE, "${r.avg.roundToInt()}°", "average")
            tile(c, 80f + tw + gap, y2, tw, th, TILE, "${r.pop}", "pop °/s")
            tile(c, 80f + 2 * (tw + gap), y2, tw, th, if (r.newBest) GOLD else ORANGE,
                if (circle) String.format(Locale.US, "%.1fx", abs(r.turns)) else if (r.newBest) "pb" else "send",
                if (circle) "turns" else if (r.newBest) "personal best" else "it", r.newBest)
        }
        footer(c)
        return bmp
    }

    /** A saved ride: the route's shape and the numbers. */
    fun rideBitmap(r: SavedRide): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(r.startedAt)).lowercase()
        c.drawText("ride · $date", 80f, 170f, paint(ORANGE, 56f))
        val km = String.format(Locale.US, "%.1f", r.distanceM / 1000)
        val big = paint(TEXT, 260f, thin)
        c.drawText(km, 64f, 430f, big)
        c.drawText("km", 84f + big.measureText(km), 430f, paint(MUTED, 70f))

        // route line in a tile
        val bx = 80f; val by = 500f; val bw = W - 160f; val bh = 420f
        c.drawRect(bx, by, bx + bw, by + bh, Paint().apply { color = TILE })
        if (r.path.size >= 2) {
            val lat0 = r.path.sumOf { it.first } / r.path.size
            val k = cos(Math.toRadians(lat0))
            val xs = r.path.map { it.second * k }; val ys = r.path.map { it.first }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
            val pad = 40f
            val s = minOf(bw - 2 * pad, bh - 2 * pad) / span
            val ox = bx + (bw - (maxX - minX) * s).toFloat() / 2
            val oy = by + (bh - (maxY - minY) * s).toFloat() / 2
            val path = Path()
            r.path.indices.forEach { i ->
                val x = (ox + (xs[i] - minX) * s).toFloat(); val y = (oy + (maxY - ys[i]) * s).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ORANGE; style = Paint.Style.STROKE; strokeWidth = 9f
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            })
        }
        val gap = 14f; val tw = (W - 160f - 2 * gap) / 3; val th = 200f; val y1 = by + bh + gap
        val h = r.durationMs / 3_600_000; val m = (r.durationMs / 60_000) % 60
        tile(c, 80f, y1, tw, th, TILE, if (h > 0) "${h}h ${m}m" else "${m}m", "time")
        tile(c, 80f + tw + gap, y1, tw, th, T_BLUE, "${(r.topSpeedMs * 3.6f).roundToInt()}", "top km/h")
        tile(c, 80f + 2 * (tw + gap), y1, tw, th, ORANGE, "${r.wheelies}", "wheelies")
        footer(c)
        return bmp
    }

    // ------------------------------------------------------------ sharing

    private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    private fun shareFile(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareBitmap(ctx: Context, bmp: Bitmap, name: String, title: String) {
        val f = File(shareDir(ctx), "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        shareFile(ctx, f, "image/png", title)
    }

    fun shareTrick(ctx: Context, r: WheelieRecord) =
        runCatching { shareBitmap(ctx, trickBitmap(r), "pulse-trick", "Share your trick") }

    fun shareRide(ctx: Context, r: SavedRide) =
        runCatching { shareBitmap(ctx, rideBitmap(r), "pulse-ride", "Share your ride") }

    /**
     * The ride as a GPX track. Saved rides keep a thinned route without
     * per-point times, so times are spread evenly over the ride (fine for
     * Strava's map and distance; speeds along the way are approximate).
     */
    fun gpx(r: SavedRide): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val n = r.path.size
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Kinetic Pulse\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><time>").append(iso.format(Date(r.startedAt))).append("</time></metadata>\n")
        sb.append("  <trk><name>Pulse ride</name><type>motorcycling</type><trkseg>\n")
        r.path.forEachIndexed { i, (lat, lon) ->
            val t = r.startedAt + if (n > 1) r.durationMs * i / (n - 1) else 0L
            sb.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n", lat, lon, iso.format(Date(t))))
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    fun shareGpx(ctx: Context, r: SavedRide) = runCatching {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(r.startedAt))
        val f = File(shareDir(ctx), "pulse-ride-$stamp.gpx")
        f.writeText(gpx(r))
        shareFile(ctx, f, "application/gpx+xml", "Export ride (GPX)")
    }
}
