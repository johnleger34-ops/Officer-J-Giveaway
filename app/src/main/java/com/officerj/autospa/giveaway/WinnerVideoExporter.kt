package com.officerj.autospa.giveaway

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class WinnerVideoExporter(
    private val context: Context,
    private val type: GiveawayType,
    private val title: String,
    private val result: WinnerResult
) {
    private val width = 540
    private val height = 960
    private val fps = 30
    private val seconds = 5
    private val frameCount = fps * seconds

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
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
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
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxerStarted = false; var frame = 0; var eosQueued = false
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
                        input.clear(); input.put(yuv)
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
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val out = codec.getOutputBuffer(outputIndex)!!
                    if (info.size > 0 && muxerStarted) {
                        out.position(info.offset); out.limit(info.offset + info.size); muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        bitmap.recycle(); codec.stop(); codec.release(); if (muxerStarted) muxer.stop(); muxer.release()
    }

    private fun drawFrame(bitmap: Bitmap, progress: Float) {
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(3, 6, 10))
        val glow = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0xFF8B1020.toInt(), 0xFF07101B.toInt(), 0xFF006DFF.toInt()), null, Shader.TileMode.CLAMP)
        p.shader = glow; c.drawRect(0f, 0f, width.toFloat(), 14f, p); p.shader = null
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.officer_j_logo)
        c.drawBitmap(logo, null, Rect(width/2-78, 32, width/2+78, 178), p)
        p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.color = Color.WHITE; p.textSize = 34f
        c.drawText("OFFICER J'S AUTO SPA", width/2f, 220f, p)
        p.color = Ui.BLUE; p.textSize = 22f; c.drawText(title.take(32).uppercase(), width/2f, 258f, p)

        if (type == GiveawayType.WHEEL) drawVideoWheel(c, p, progress) else drawVideoRaffle(c, p, logo, progress)
        val reveal = ((progress - .72f) / .16f).coerceIn(0f, 1f)
        if (reveal > 0f) {
            p.color = Color.argb((235*reveal).toInt(), 4, 8, 14); c.drawRoundRect(42f, 700f, width-42f, 900f, 28f, 28f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.argb((255*reveal).toInt(), 8, 123, 255); c.drawRoundRect(42f, 700f, width-42f, 900f, 28f, 28f, p); p.style = Paint.Style.FILL
            p.color = Color.WHITE; p.textSize = 25f; c.drawText("WINNER", width/2f, 748f, p)
            p.textSize = 43f; c.drawText(result.name.take(24), width/2f, 813f, p)
            p.color = Ui.BLUE; p.textSize = 24f; c.drawText("ENTRY #${result.ticketNumber}", width/2f, 860f, p)
        }
        logo.recycle()
    }

    private fun drawVideoWheel(c: Canvas, p: Paint, progress: Float) {
        val cx = width/2f; val cy = 475f; val r = 180f
        val rotation = if (progress < .78f) (progress/.78f) * 2880f * (1f - .35f*progress) else 2880f*.65f
        val colors = intArrayOf(0xFF0A1726.toInt(), 0xFF087BFF.toInt())
        c.save(); c.rotate(rotation, cx, cy)
        val rect = RectF(cx-r, cy-r, cx+r, cy+r)
        repeat(12) { i -> p.color=colors[i%2]; c.drawArc(rect, i*30f, 30f, true, p) }
        c.restore()
        p.color=0xFF03060A.toInt(); c.drawCircle(cx,cy,55f,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=5f; p.color=Ui.BLUE; c.drawCircle(cx,cy,r,p); c.drawCircle(cx,cy,55f,p); p.style=Paint.Style.FILL
        val path=Path().apply { moveTo(cx,cy-r-18); lineTo(cx-22,cy-r-55); lineTo(cx+22,cy-r-55); close() }
        p.color=Color.WHITE; c.drawPath(path,p)
    }

    private fun drawVideoRaffle(c: Canvas, p: Paint, logo: Bitmap, progress: Float) {
        p.color=0xFF0A1019.toInt(); c.drawRoundRect(95f,430f,445f,675f,28f,28f,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=5f; p.color=0xFF667789.toInt(); c.drawRoundRect(95f,430f,445f,675f,28f,28f,p); p.style=Paint.Style.FILL
        val rise=(progress/.72f).coerceIn(0f,1f); val y=500f-rise*210f
        p.color=Color.WHITE; c.drawRoundRect(120f,y,420f,y+185f,18f,18f,p)
        c.drawBitmap(logo,null,Rect(230,y.toInt()+12,310,y.toInt()+85),p)
        p.color=Color.BLACK; p.textSize=31f; p.textAlign=Paint.Align.CENTER; c.drawText(result.name.take(22),270f,y+125f,p)
        p.textSize=21f; c.drawText("Raffle #${result.ticketNumber}",270f,y+160f,p)
    }

    private fun argbToYuv(bitmap: Bitmap, semiPlanar: Boolean): ByteArray {
        val argb = IntArray(width*height); bitmap.getPixels(argb,0,width,0,0,width,height)
        val ySize=width*height; val uvSize=ySize/4; val out=ByteArray(ySize+uvSize*2)
        var yi=0; var ui=ySize; var vi=ySize+uvSize
        for (j in 0 until height) for (i in 0 until width) {
            val color=argb[j*width+i]; val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
            val y=((66*r+129*g+25*b+128 shr 8)+16).coerceIn(0,255)
            val u=((-38*r-74*g+112*b+128 shr 8)+128).coerceIn(0,255)
            val v=((112*r-94*g-18*b+128 shr 8)+128).coerceIn(0,255)
            out[yi++]=y.toByte()
            if (j%2==0 && i%2==0) {
                if (semiPlanar) { out[ui++]=u.toByte(); out[ui++]=v.toByte() }
                else { out[ui++]=u.toByte(); out[vi++]=v.toByte() }
            }
        }
        return out
    }
}
