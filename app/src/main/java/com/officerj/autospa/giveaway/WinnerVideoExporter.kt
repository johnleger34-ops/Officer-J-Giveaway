package com.officerj.autospa.giveaway

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class WinnerVideoExporter(
    private val context: Context,
    private val type: GiveawayType,
    private val title: String,
    private val result: WinnerResult,
    private val tickets: List<Pair<Int, String>>
) {
    private data class Segment(val name: String, val tickets: List<Pair<Int, String>>) {
        val weight: Int get() = tickets.size
    }

    private val width = 540
    private val height = 960
    private val fps = 30
    private val seconds = 8
    private val frameCount = fps * seconds
    private val segments by lazy { buildSegments(tickets) }
    private val logo by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.officer_j_logo) }

    fun export(): String? {
        val temp = File(context.cacheDir, "officer_j_${System.currentTimeMillis()}.mp4")
        encode(temp)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "OfficerJ_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Officer J Giveaways")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        temp.delete()
        return uri.toString()
    }

    private fun encode(file: File) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codec = MediaCodec.createEncoderByType(mime)
        val caps = codec.codecInfo.getCapabilitiesForType(mime).colorFormats.toSet()
        val semi = caps.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        val colorFormat = if (semi) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        var frame = 0
        var eosQueued = false
        val info = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        while (true) {
            if (!eosQueued) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)!!
                    if (frame < frameCount) {
                        drawFrame(bitmap, frame.toFloat() / (frameCount - 1))
                        val yuv = argbToYuv(bitmap, semi)
                        input.clear()
                        input.put(yuv)
                        codec.queueInputBuffer(inputIndex, 0, yuv.size, frame * 1_000_000L / fps, 0)
                        frame++
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, frame * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val out = codec.getOutputBuffer(outputIndex)!!
                    if (info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        bitmap.recycle()
        logo.recycle()
        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    private fun drawFrame(bitmap: Bitmap, progress: Float) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(3, 6, 10))

        val glow = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0xFF8B1020.toInt(), 0xFF07101B.toInt(), 0xFF006DFF.toInt()), null, Shader.TileMode.CLAMP)
        paint.shader = glow
        canvas.drawRect(0f, 0f, width.toFloat(), 14f, paint)
        paint.shader = null

        canvas.drawBitmap(logo, null, Rect(width / 2 - 68, 25, width / 2 + 68, 153), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.WHITE
        paint.textSize = 31f
        canvas.drawText("OFFICER J'S AUTO SPA", width / 2f, 193f, paint)
        paint.color = Ui.BLUE
        paint.textSize = 21f
        canvas.drawText("WHEEL SPINNER", width / 2f, 226f, paint)
        paint.color = Color.WHITE
        paint.textSize = 24f
        canvas.drawText(fitText(title.uppercase(), 450f, paint), width / 2f, 264f, paint)

        if (type == GiveawayType.WHEEL) drawVideoWheel(canvas, paint, progress) else drawVideoRaffle(canvas, paint, progress)

        val reveal = ((progress - 0.75f) / 0.08f).coerceIn(0f, 1f)
        if (reveal > 0f) {
            paint.color = Color.argb((238 * reveal).toInt(), 4, 8, 14)
            canvas.drawRoundRect(42f, 748f, width - 42f, 915f, 28f, 28f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.argb((255 * reveal).toInt(), 8, 123, 255)
            canvas.drawRoundRect(42f, 748f, width - 42f, 915f, 28f, 28f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 24f
            canvas.drawText("WINNER", width / 2f, 790f, paint)
            paint.textSize = 39f
            canvas.drawText(fitText(result.name, 420f, paint), width / 2f, 845f, paint)
            paint.color = Ui.BLUE
            paint.textSize = 23f
            canvas.drawText("ENTRY #${result.ticketNumber}", width / 2f, 885f, paint)
        }
    }

    private fun drawVideoWheel(canvas: Canvas, paint: Paint, progress: Float) {
        val cx = width / 2f
        val cy = 493f
        val radius = 205f
        val spinProgress = (progress / 0.625f).coerceIn(0f, 1f) // first five seconds
        val eased = 1f - (1f - spinProgress) * (1f - spinProgress) * (1f - spinProgress)
        val finalRotation = winningFinalRotation()
        val rotation = finalRotation * eased
        drawWheel(canvas, paint, cx, cy, radius, rotation)
    }

    private fun drawWheel(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, rotation: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF07111F.toInt()
        canvas.drawCircle(cx, cy, radius + 7f, paint)

        if (segments.isNotEmpty()) {
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val totalWeight = tickets.size.toFloat().coerceAtLeast(1f)
            var start = 0f
            segments.forEachIndexed { index, segment ->
                val sweep = 360f * segment.weight / totalWeight
                paint.style = Paint.Style.FILL
                paint.color = if (index % 2 == 0) 0xFF0E1A29.toInt() else 0xFF075BC4.toInt()
                canvas.drawArc(rect, start, sweep, true, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (segments.size > 300) 0.5f else 1.2f
                paint.color = 0xFF6EAFFF.toInt()
                canvas.drawArc(rect, start, sweep, true, paint)
                paint.style = Paint.Style.FILL
                drawSegmentName(canvas, segment.name, start + sweep / 2f, sweep, cx, cy, radius)
                start += sweep
            }
            canvas.restore()
        }

        paint.color = 0xFF05070A.toInt()
        canvas.drawCircle(cx, cy, radius * 0.25f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Ui.BLUE
        canvas.drawCircle(cx, cy, radius * 0.25f, paint)
        paint.style = Paint.Style.FILL
        val logoHalf = (radius * 0.19f).toInt()
        canvas.drawBitmap(logo, null, Rect((cx - logoHalf).toInt(), (cy - logoHalf).toInt(), (cx + logoHalf).toInt(), (cy + logoHalf).toInt()), paint)

        drawPointerName(canvas, paint, cx, cy, radius, rotation)
        val pointer = Path().apply {
            moveTo(cx, cy - radius + 30f)
            lineTo(cx - 25f, cy - radius - 20f)
            lineTo(cx + 25f, cy - radius - 20f)
            close()
        }
        paint.color = Ui.BLUE
        canvas.drawPath(pointer, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawPath(pointer, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawSegmentName(canvas: Canvas, name: String, centerAngle: Float, sweep: Float, cx: Float, cy: Float, radius: Float) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            val angularRoom = (Math.toRadians(sweep.toDouble()) * radius * 0.72f).toFloat()
            textSize = (angularRoom * 0.34f).coerceIn(6.5f, 16f)
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val label = fitText(name, radius * 0.56f, textPaint)
        val radians = Math.toRadians(centerAngle.toDouble())
        val textRadius = radius * 0.66f
        val x = cx + cos(radians).toFloat() * textRadius
        val y = cy + sin(radians).toFloat() * textRadius
        canvas.save()
        canvas.rotate(centerAngle, x, y)
        val normalized = normalize(centerAngle)
        if (normalized in 90f..270f) canvas.rotate(180f, x, y)
        canvas.drawText(label, x, y + textPaint.textSize * 0.34f, textPaint)
        canvas.restore()
    }

    private fun drawPointerName(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, rotation: Float) {
        if (segments.isEmpty()) return
        val pointerAngle = normalize(270f - rotation)
        val total = tickets.size.toFloat().coerceAtLeast(1f)
        var start = 0f
        val active = segments.firstOrNull { segment ->
            val sweep = 360f * segment.weight / total
            val inside = pointerAngle >= start && pointerAngle < start + sweep
            start += sweep
            inside
        } ?: segments.last()
        val rect = RectF(cx - radius * 0.60f, cy - radius * 0.88f, cx + radius * 0.60f, cy - radius * 0.72f)
        paint.color = 0xE8050A12.toInt()
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Ui.BLUE
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(fitText(active.name, rect.width() * 0.88f, paint), cx, rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun winningFinalRotation(): Float {
        if (segments.isEmpty() || tickets.isEmpty()) return 2880f
        val winnerIndex = segments.indexOfFirst { segment -> segment.tickets.any { it.first == result.ticketNumber } }.coerceAtLeast(0)
        val total = tickets.size.toFloat()
        var start = 0f
        for (i in 0 until winnerIndex) start += 360f * segments[i].weight / total
        val sweep = 360f * segments[winnerIndex].weight / total
        val center = start + sweep / 2f
        val align = normalize(270f - center)
        return 360f * 8f + align
    }

    private fun buildSegments(source: List<Pair<Int, String>>): List<Segment> {
        val grouped = linkedMapOf<String, Pair<String, MutableList<Pair<Int, String>>>>()
        source.forEach { ticket ->
            val display = ticket.second.trim().ifBlank { "Unnamed" }
            val key = display.lowercase(Locale.ROOT)
            val existing = grouped[key]
            if (existing == null) grouped[key] = display to mutableListOf(ticket) else existing.second += ticket
        }
        return grouped.values.map { Segment(it.first, it.second.toList()) }
    }

    private fun drawVideoRaffle(canvas: Canvas, paint: Paint, progress: Float) {
        paint.color = 0xFF0A1019.toInt()
        canvas.drawRoundRect(95f, 430f, 445f, 675f, 28f, 28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0xFF667789.toInt()
        canvas.drawRoundRect(95f, 430f, 445f, 675f, 28f, 28f, paint)
        paint.style = Paint.Style.FILL
        val rise = (progress / 0.625f).coerceIn(0f, 1f)
        val y = 500f - rise * 210f
        paint.color = Color.WHITE
        canvas.drawRoundRect(120f, y, 420f, y + 185f, 18f, 18f, paint)
        canvas.drawBitmap(logo, null, Rect(230, y.toInt() + 12, 310, y.toInt() + 85), paint)
        paint.color = Color.BLACK
        paint.textSize = 31f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(fitText(result.name, 270f, paint), 270f, y + 125f, paint)
        paint.textSize = 21f
        canvas.drawText("Raffle #${result.ticketNumber}", 270f, y + 160f, paint)
    }

    private fun fitText(value: String, maxWidth: Float, paint: Paint): String {
        val clean = value.trim().ifBlank { "Unnamed" }
        if (paint.measureText(clean) <= maxWidth) return clean
        var end = clean.length
        while (end > 1 && paint.measureText(clean.substring(0, end) + "…") > maxWidth) end--
        return clean.substring(0, end.coerceAtLeast(1)) + "…"
    }

    private fun normalize(value: Float): Float {
        val n = value % 360f
        return if (n < 0f) n + 360f else n
    }

    private fun argbToYuv(bitmap: Bitmap, semiPlanar: Boolean): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val ySize = width * height
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        var yi = 0
        var ui = ySize
        var vi = ySize + uvSize
        for (j in 0 until height) for (i in 0 until width) {
            val color = argb[j * width + i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val y = ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(0, 255)
            val u = ((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128).coerceIn(0, 255)
            val v = ((112 * r - 94 * g - 18 * b + 128 shr 8) + 128).coerceIn(0, 255)
            out[yi++] = y.toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                if (semiPlanar) {
                    out[ui++] = u.toByte()
                    out[ui++] = v.toByte()
                } else {
                    out[ui++] = u.toByte()
                    out[vi++] = v.toByte()
                }
            }
        }
        return out
    }
}
