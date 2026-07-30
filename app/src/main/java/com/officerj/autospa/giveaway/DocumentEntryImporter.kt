package com.officerj.autospa.giveaway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads giveaway entries from TXT, CSV, JSON, PDF, XLSX, and DOCX files. */
class DocumentEntryImporter(private val context: Context) {

    fun read(uri: Uri): String {
        val name = displayName(uri).lowercase(Locale.ROOT)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        return when {
            name.endsWith(".pdf") || mime == "application/pdf" -> readPdf(uri)
            name.endsWith(".xlsx") || mime.contains("spreadsheetml") -> readXlsx(uri)
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(uri)
            name.endsWith(".xls") -> throw IllegalArgumentException("Older .xls files are not supported. Save the sheet as .xlsx or CSV.")
            name.endsWith(".doc") -> throw IllegalArgumentException("Older .doc files are not supported. Save the document as .docx or PDF.")
            else -> context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Could not open the selected file")
        }
    }

    private fun readPdf(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open PDF")
        PDDocument.load(bytes).use { document ->
            val text = PDFTextStripper().getText(document).trim()
            if (text.isBlank()) {
                throw IllegalArgumentException("No selectable text was found in this PDF. Scanned-image PDFs must be converted to text first.")
            }
            return text
        }
    }

    private fun readDocx(uri: Uri): String {
        val entries = unzip(uri)
        val xml = entries["word/document.xml"]
            ?: throw IllegalArgumentException("This DOCX file does not contain readable document text")
        val doc = parseXml(xml)
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val lines = mutableListOf<String>()
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val texts = paragraph.getElementsByTagNameNS("*", "t")
            val line = buildString {
                for (j in 0 until texts.length) append(texts.item(j).textContent)
            }.trim()
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
                    val value = when (type) {
                        "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                        else -> raw
                    }.trim()
                    values += value
                }
                if (values.any { it.isNotBlank() }) {
                    val name = values.getOrNull(0).orEmpty().trim()
                    val quantity = values.getOrNull(1).orEmpty().trim().toDoubleOrNull()?.toInt()
                    val lower = name.lowercase(Locale.ROOT)
                    if (name.isNotBlank() && lower !in setOf("name", "participant_name", "participant name", "entrant", "entries")) {
                        output += if (quantity != null && quantity > 0) "$name,$quantity" else name
                    }
                }
            }
        }
        if (output.isEmpty()) throw IllegalArgumentException("No names were found in this Excel file")
        return output.joinToString("\n")
    }

    private fun unzip(uri: Uri): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open selected document")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
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
