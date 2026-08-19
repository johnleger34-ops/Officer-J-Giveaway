package com.officerj.autospa.giveaway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads giveaway entries from common document, spreadsheet, and text formats. */
class DocumentEntryImporter(private val context: Context) {

    fun read(uri: Uri): String {
        val name = displayName(uri).lowercase(Locale.ROOT)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        return when {
            name.endsWith(".pdf") || mime == "application/pdf" -> readPdf(uri)
            name.endsWith(".xlsx") || mime.contains("spreadsheetml") -> readXlsx(uri)
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(uri)
            name.endsWith(".ods") || mime.contains("opendocument.spreadsheet") -> readOds(uri)
            name.endsWith(".rtf") || mime == "application/rtf" || mime == "text/rtf" -> readRtf(uri)
            name.endsWith(".html") || name.endsWith(".htm") || mime.contains("html") -> readHtml(uri)
            name.endsWith(".xls") -> throw IllegalArgumentException("Legacy .xls is selectable, but this binary format cannot be safely decoded on-device. Export it as .xlsx or CSV.")
            name.endsWith(".doc") -> throw IllegalArgumentException("Legacy .doc is selectable, but this binary format cannot be safely decoded on-device. Export it as .docx, PDF, or TXT.")
            else -> readTextLike(uri)
        }
    }

    private fun readTextLike(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open selected file")
        if (bytes.isEmpty()) return ""
        if (bytes.any { it == 0.toByte() }) throw IllegalArgumentException("This appears to be a binary file format that does not contain directly readable text")
        return decodeText(bytes).trim()
    }

    private fun decodeText(bytes: ByteArray): String {
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> runCatching { String(bytes, Charsets.UTF_8) }.getOrElse { String(bytes, Charset.forName("windows-1252")) }
        }
    }

    private fun readPdf(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open PDF")
        PDDocument.load(bytes).use { document ->
            val text = PDFTextStripper().getText(document).trim()
            if (text.isBlank()) throw IllegalArgumentException("No selectable text was found in this PDF. Image-only/scanned PDFs need text recognition first.")
            return text
        }
    }

    private fun readDocx(uri: Uri): String {
        val entries = unzip(uri)
        val xml = entries["word/document.xml"] ?: throw IllegalArgumentException("This DOCX does not contain readable document text")
        val doc = parseXml(xml)
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val lines = mutableListOf<String>()
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val texts = paragraph.getElementsByTagNameNS("*", "t")
            val line = buildString { for (j in 0 until texts.length) append(texts.item(j).textContent) }.trim()
            if (line.isNotBlank()) lines += line
        }
        if (lines.isEmpty()) throw IllegalArgumentException("No names were found in this Word document")
        return lines.joinToString("\n")
    }

    private fun readXlsx(uri: Uri): String {
        val entries = unzip(uri)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { bytes ->
            val doc = parseXml(bytes)
            val items = doc.getElementsByTagNameNS("*", "si")
            (0 until items.length).map { i ->
                val item = items.item(i) as Element
                val texts = item.getElementsByTagNameNS("*", "t")
                buildString { for (j in 0 until texts.length) append(texts.item(j).textContent) }
            }
        }.orEmpty()
        val worksheetNames = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted()
        if (worksheetNames.isEmpty()) throw IllegalArgumentException("No worksheet was found in this Excel file")
        val output = mutableListOf<String>()
        worksheetNames.forEach { sheetName ->
            val doc = parseXml(entries.getValue(sheetName))
            val rows = doc.getElementsByTagNameNS("*", "row")
            for (i in 0 until rows.length) {
                val row = rows.item(i) as? Element ?: continue
                val cells = row.getElementsByTagNameNS("*", "c")
                val values = mutableListOf<String>()
                for (j in 0 until cells.length) {
                    val cell = cells.item(j) as Element
                    val type = cell.getAttribute("t")
                    val valueNode = cell.getElementsByTagNameNS("*", "v").item(0)
                    val inlineNode = cell.getElementsByTagNameNS("*", "t").item(0)
                    val raw = valueNode?.textContent ?: inlineNode?.textContent.orEmpty()
                    values += when (type) { "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty(); else -> raw }.trim()
                }
                appendSpreadsheetRow(output, values)
            }
        }
        if (output.isEmpty()) throw IllegalArgumentException("No names were found in this Excel file")
        return output.joinToString("\n")
    }

    private fun readOds(uri: Uri): String {
        val entries = unzip(uri)
        val xml = entries["content.xml"] ?: throw IllegalArgumentException("This ODS file has no readable worksheet content")
        val doc = parseXml(xml)
        val rows = doc.getElementsByTagNameNS("*", "table-row")
        val output = mutableListOf<String>()
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? Element ?: continue
            val cells = row.getElementsByTagNameNS("*", "table-cell")
            val values = mutableListOf<String>()
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as Element
                val ps = cell.getElementsByTagNameNS("*", "p")
                val text = buildString { for (k in 0 until ps.length) { if (k > 0) append(' '); append(ps.item(k).textContent) } }.trim()
                values += text
            }
            appendSpreadsheetRow(output, values)
        }
        if (output.isEmpty()) throw IllegalArgumentException("No names were found in this ODS file")
        return output.joinToString("\n")
    }

    private fun appendSpreadsheetRow(output: MutableList<String>, values: List<String>) {
        if (values.none { it.isNotBlank() }) return
        val name = values.getOrNull(0).orEmpty().trim()
        val quantity = values.getOrNull(1).orEmpty().trim().removeSuffix("x").toDoubleOrNull()?.toInt()
        val lower = name.lowercase(Locale.ROOT)
        if (name.isNotBlank() && lower !in setOf("name", "participant_name", "participant name", "entrant", "entries")) {
            output += if (quantity != null && quantity > 0) "$name,$quantity" else name
        }
    }

    private fun readRtf(uri: Uri): String {
        val raw = readTextLike(uri)
        return raw
            .replace(Regex("\\\\par[d]?\\b ?"), "\n")
            .replace(Regex("\\\\'[0-9a-fA-F]{2}")) { m -> m.value.substring(2).toInt(16).toChar().toString() }
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace(Regex("[{}]"), "")
            .lines().joinToString("\n") { it.trim() }.trim()
    }

    private fun readHtml(uri: Uri): String = readTextLike(uri)
        .replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .lines().joinToString("\n") { it.trim() }.trim()

    private fun unzip(uri: Uri): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open selected document")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
