package com.officerj.autospa.giveaway

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import org.json.JSONArray
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var store: GiveawayStore
    private val giveaways = mutableListOf<Giveaway>()
    private var currentId: String? = null
    private var pendingImportId: String? = null
    private var pendingExportId: String? = null

    private val openText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }
    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportToUri(uri)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        store = GiveawayStore(this)
        giveaways += store.load()
        showDashboard()
    }

    private fun baseScreen(title: String, back: (() -> Unit)? = null): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(12))
            background = Ui.bg(0xFF03060A.toInt(), 0)
        }
        if (back != null) {
            val backBtn = Ui.button(this, "← BACK", true).apply { setOnClickListener { back.invoke() } }
            val row = LinearLayout(this).apply { gravity = Gravity.START }
            row.addView(backBtn, LinearLayout.LayoutParams(dp(105), dp(44)))
            header.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.officer_j_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(108), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        header.addView(Ui.text(this, "OFFICER J'S AUTO SPA", 23f, Ui.SILVER, true).apply { gravity = Gravity.CENTER })
        header.addView(Ui.text(this, title.uppercase(), 15f, Ui.BLUE, true).apply { gravity = Gravity.CENTER })
        val lights = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        lights.addView(View(this).apply { background = Ui.bg(0xFFB31425.toInt(), 20) }, LinearLayout.LayoutParams(dp(90), dp(4)).apply { marginEnd = dp(6) })
        lights.addView(View(this).apply { background = Ui.bg(Ui.BLUE, 20) }, LinearLayout.LayoutParams(dp(90), dp(4)))
        header.addView(lights)
        root.addView(header)
        return root
    }

    private fun showDashboard() {
        currentId = null
        val root = baseScreen("Giveaway Command Center")
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(30))
        }

        val intro = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "CREATE A GIVEAWAY", 20f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Unlimited saved wheels and raffles. Every entry receives its own numbered chance.", 14f, Ui.MUTED, false), LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        body.addView(intro)

        val wheelBtn = featureButton("WHEEL SPINNER", "Animated five-second branded wheel", "CREATE WHEEL") { promptCreate(GiveawayType.WHEEL) }
        body.addView(wheelBtn, marginTop(14))
        val raffleBtn = featureButton("RAFFLE DRAW", "Animated ticket box and winner slip", "CREATE RAFFLE") { promptCreate(GiveawayType.RAFFLE) }
        body.addView(raffleBtn, marginTop(12))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backup = Ui.button(this, "EXPORT ALL", true).apply { setOnClickListener { createBackup.launch("officer-j-giveaway-backup.json") } }
        val restore = Ui.button(this, "IMPORT ALL", true).apply { setOnClickListener { openBackup.launch(arrayOf("application/json", "text/plain")) } }
        tools.addView(backup, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        tools.addView(restore, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        body.addView(tools, marginTop(14))

        body.addView(Ui.text(this, "SAVED GIVEAWAYS", 18f, Ui.SILVER, true), marginTop(20))
        if (giveaways.isEmpty()) {
            body.addView(Ui.card(this).apply {
                gravity = Gravity.CENTER
                addView(Ui.text(this@MainActivity, "No saved giveaways yet", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER })
            }, marginTop(10))
        } else {
            giveaways.sortedByDescending { it.createdAt }.forEach { g -> body.addView(giveawayCard(g), marginTop(10)) }
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun featureButton(title: String, subtitle: String, action: String, click: () -> Unit): View {
        val card = Ui.card(this, 16)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = TextView(this).apply {
            text = if (title.startsWith("WHEEL")) "◉" else "▣"
            textSize = 38f; gravity = Gravity.CENTER; setTextColor(Ui.BLUE)
            background = Ui.bg(Ui.PANEL2, dp(12), dp(1), Ui.BLUE)
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(14) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(Ui.text(this, title, 19f, Ui.SILVER, true))
        labels.addView(Ui.text(this, subtitle, 13f, Ui.MUTED))
        top.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        card.addView(Ui.button(this, action).apply { setOnClickListener { click() } }, marginTop(12))
        return card
    }

    private fun giveawayCard(g: Giveaway): View {
        val card = Ui.card(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(Ui.text(this@MainActivity, g.title, 17f, Ui.SILVER, true))
        labels.addView(Ui.text(this@MainActivity, "${if (g.type == GiveawayType.WHEEL) "Wheel" else "Raffle"} • ${g.totalEntries} entries • ${g.winners.size} results", 13f, Ui.MUTED))
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val open = Ui.button(this, "OPEN").apply { setOnClickListener { showEditor(g.id) } }
        row.addView(open, LinearLayout.LayoutParams(dp(90), dp(44)))
        card.addView(row)
        val delete = Ui.button(this, "DELETE GIVEAWAY", true).apply {
            setTextColor(Ui.RED)
            setOnClickListener { confirmDelete(g) }
        }
        card.addView(delete, marginTop(10))
        return card
    }

    private fun promptCreate(type: GiveawayType) {
        val input = Ui.input(this, if (type == GiveawayType.WHEEL) "Wheel name" else "Raffle name")
        AlertDialog.Builder(this).setTitle("New ${if (type == GiveawayType.WHEEL) "Wheel" else "Raffle"}")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                val title = input.text.toString().trim().ifBlank { if (type == GiveawayType.WHEEL) "New Wheel" else "New Raffle" }
                val g = Giveaway(title = title, type = type)
                giveaways += g; persist(); showEditor(g.id)
            }.show()
    }

    private fun showEditor(id: String) {
        val g = giveaways.firstOrNull { it.id == id } ?: return showDashboard()
        currentId = id
        val root = baseScreen(if (g.type == GiveawayType.WHEEL) "Wheel Spinner" else "Raffle Draw") { showDashboard() }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(35)) }

        val titleCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, g.title, 22f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "${g.totalEntries} numbered entries", 14f, Ui.BLUE, true))
        }
        body.addView(titleCard)

        val visual: View = if (g.type == GiveawayType.WHEEL) WheelView(this).apply { tickets = g.expandedTickets() } else RaffleView(this).apply { tickets = g.expandedTickets() }
        body.addView(visual, LinearLayout.LayoutParams.MATCH_PARENT, dp(390))

        val action = Ui.button(this, if (g.type == GiveawayType.WHEEL) "SPIN THE WHEEL" else "DRAW A WINNER")
        action.setOnClickListener {
            if (g.totalEntries == 0) toast("Add at least one entry first")
            else if (visual is WheelView) visual.spin { selected -> onWinner(g, selected) }
            else if (visual is RaffleView) visual.drawWinner { selected -> onWinner(g, selected) }
        }
        body.addView(action, marginTop(4))

        val addCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "ADD ENTRIES", 18f, Ui.SILVER, true))
            val name = Ui.input(this@MainActivity, "Name")
            val qty = Ui.input(this@MainActivity, "Quantity").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(name, LinearLayout.LayoutParams(0, dp(48), 2f).apply { marginEnd = dp(5) })
            row.addView(qty, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            addView(row, marginTop(10))
            addView(Ui.button(this@MainActivity, "ADD TO ${if (g.type == GiveawayType.WHEEL) "WHEEL" else "RAFFLE"}").apply {
                setOnClickListener {
                    val n = name.text.toString().trim(); val q = qty.text.toString().toIntOrNull()?.coerceIn(1, 100000) ?: 1
                    if (n.isBlank()) toast("Enter a name") else {
                        g.entries += EntryGroup(name = n, quantity = q); persist(); showEditor(g.id)
                    }
                }
            }, marginTop(10))
        }
        body.addView(addCard, marginTop(14))

        val ioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ioRow.addView(Ui.button(this, "PASTE LIST", true).apply { setOnClickListener { pasteListDialog(g) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        ioRow.addView(Ui.button(this, "IMPORT FILE", true).apply { setOnClickListener { pendingImportId = g.id; openText.launch(arrayOf("text/plain", "text/csv", "application/json", "application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        ioRow.addView(Ui.button(this, "EXPORT", true).apply { setOnClickListener { pendingExportId = g.id; createExport.launch(safeFileName(g.title) + ".csv") } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        body.addView(ioRow, marginTop(10))

        body.addView(Ui.text(this, "ENTRY LIST", 18f, Ui.SILVER, true), marginTop(20))
        if (g.entries.isEmpty()) body.addView(Ui.text(this, "No entries added", 14f, Ui.MUTED), marginTop(8))
        else g.entries.forEachIndexed { index, e -> body.addView(entryRow(g, e, index), marginTop(7)) }

        if (g.winners.isNotEmpty()) {
            body.addView(Ui.text(this, "WINNER HISTORY", 18f, Ui.SILVER, true), marginTop(20))
            g.winners.asReversed().forEach { w ->
                body.addView(Ui.card(this, 10).apply {
                    addView(Ui.text(this@MainActivity, "${w.name} • Entry #${w.ticketNumber}", 15f, Ui.SILVER, true))
                    addView(Ui.text(this@MainActivity, dateFmt(w.timestamp), 12f, Ui.MUTED))
                }, marginTop(7))
            }
        }

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun entryRow(g: Giveaway, e: EntryGroup, index: Int): View {
        val card = Ui.card(this, 10)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val start = g.entries.take(index).sumOf { it.quantity } + 1
        val end = start + e.quantity - 1
        val label = Ui.text(this, "${e.name}\nEntries #$start${if (end > start) "–$end" else ""} (${e.quantity})", 14f, Ui.SILVER, true)
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, "EDIT", true).apply { setOnClickListener { editEntry(g, e) } }, LinearLayout.LayoutParams(dp(75), dp(42)).apply { marginEnd = dp(5) })
        row.addView(Ui.button(this, "X", true).apply { setTextColor(Ui.RED); setOnClickListener { g.entries.remove(e); persist(); showEditor(g.id) } }, LinearLayout.LayoutParams(dp(48), dp(42)))
        card.addView(row)
        return card
    }

    private fun editEntry(g: Giveaway, e: EntryGroup) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18)) }
        val name = Ui.input(this, "Name").apply { setText(e.name) }
        val qty = Ui.input(this, "Quantity").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(e.quantity.toString()) }
        box.addView(name); box.addView(qty, marginTop(8))
        AlertDialog.Builder(this).setTitle("Edit entry").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            e.name = name.text.toString().trim().ifBlank { e.name }; e.quantity = qty.text.toString().toIntOrNull()?.coerceIn(1, 100000) ?: e.quantity
            persist(); showEditor(g.id)
        }.show()
    }

    private fun pasteListDialog(g: Giveaway) {
        val input = EditText(this).apply {
            hint = "One name per line\nJohn Smith,5\nJane Doe x3"
            setTextColor(Ui.SILVER); setHintTextColor(Ui.MUTED); setBackgroundColor(Ui.PANEL); minLines = 10; gravity = Gravity.TOP
            setPadding(dp(12)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle("Paste entry list").setMessage("Use Name,Quantity or Name xQuantity. A plain name receives one entry.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ ->
                val parsed = parseEntries(input.text.toString()); g.entries += parsed; persist(); showEditor(g.id); toast("Imported ${parsed.sumOf { it.quantity }} entries")
            }.show()
    }

    private fun parseEntries(text: String): List<EntryGroup> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim(); if (line.isBlank()) return@mapNotNull null
        val normalizedHeader = line.lowercase(Locale.ROOT).replace("_", " ")
        if (normalizedHeader in setOf("name", "name,entries", "name,quantity", "participant name", "participant name,entries", "participant name,quantity")) return@mapNotNull null
        val csv = line.split(",", limit = 2)
        val xMatch = Regex("^(.*?)(?:\\s+[xX×](\\d+))$").matchEntire(line)
        when {
            csv.size == 2 && csv[1].trim().toIntOrNull() != null -> EntryGroup(name = csv[0].trim(), quantity = csv[1].trim().toInt().coerceIn(1,100000))
            xMatch != null -> EntryGroup(name = xMatch.groupValues[1].trim(), quantity = xMatch.groupValues[2].toInt().coerceIn(1,100000))
            else -> EntryGroup(name = line, quantity = 1)
        }
    }.filter { it.name.isNotBlank() }.toList()

    private fun onWinner(g: Giveaway, selected: Pair<Int, String>) {
        val result = WinnerResult(selected.second, selected.first)
        AlertDialog.Builder(this).setTitle("WINNER SELECTED")
            .setMessage("${result.name}\nEntry #${result.ticketNumber}")
            .setNegativeButton("Spin/Draw Again", null)
            .setNeutralButton("Remove Winning Entry") { _, _ -> removeSpecificTicket(g, result.ticketNumber) }
            .setPositiveButton("SAVE RESULT") { _, _ ->
                g.winners += result; persist(); showWinnerActions(g, result)
            }.show()
    }

    private fun showWinnerActions(g: Giveaway, result: WinnerResult) {
        AlertDialog.Builder(this).setTitle("Result saved")
            .setMessage("Save an eight-second Officer J branded result video to your phone gallery?")
            .setNegativeButton("Later") { _, _ -> showEditor(g.id) }
            .setPositiveButton("CREATE VIDEO") { _, _ ->
                toast("Creating video. Keep the app open.")
                Thread {
                    val saved = runCatching { WinnerVideoExporter(this, g.type, g.title, result, g.expandedTickets()).export() }.getOrNull()
                    runOnUiThread {
                        if (saved != null) toast("Video saved to gallery") else toast("Video could not be created on this device")
                        showEditor(g.id)
                    }
                }.start()
            }.show()
    }

    private fun removeSpecificTicket(g: Giveaway, ticketNumber: Int) {
        var cursor = 1
        val iterator = g.entries.listIterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (ticketNumber in cursor until cursor + e.quantity) {
                if (e.quantity > 1) e.quantity-- else iterator.remove()
                break
            }
            cursor += e.quantity
        }
        persist(); showEditor(g.id)
    }

    private fun importFromUri(uri: Uri) {
        val g = giveaways.firstOrNull { it.id == pendingImportId } ?: return
        toast("Reading file…")
        Thread {
            val result = runCatching { DocumentEntryImporter(this).read(uri) }
            runOnUiThread {
                result.onSuccess { importedText ->
                    val parsed = if (importedText.trim().startsWith("[")) {
                        runCatching {
                            val a = JSONArray(importedText)
                            (0 until a.length()).map { i ->
                                val o = a.getJSONObject(i)
                                EntryGroup(
                                    name = o.optString("name"),
                                    quantity = o.optInt("quantity", 1).coerceAtLeast(1)
                                )
                            }
                        }.getOrElse { parseEntries(importedText) }
                    } else {
                        parseEntries(importedText)
                    }
                    val usable = parsed.filter { it.name.isNotBlank() }
                    if (usable.isEmpty()) {
                        toast("No names were found in that file")
                    } else {
                        g.entries += usable
                        persist()
                        showEditor(g.id)
                        toast("Imported ${usable.sumOf { it.quantity }} entries")
                    }
                }.onFailure { error ->
                    toast(error.message ?: "Could not read file")
                }
            }
        }.start()
    }

    private fun exportToUri(uri: Uri) {
        val g = giveaways.firstOrNull { it.id == pendingExportId } ?: return
        val csv = buildString {
            appendLine("Name,Quantity,First Number,Last Number")
            var n = 1
            g.entries.forEach { e ->
                val end = n + e.quantity - 1
                appendLine("\"${e.name.replace("\"", "\"\"")}\",${e.quantity},$n,$end")
                n = end + 1
            }
        }
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
        toast("Entry list exported")
    }

    private fun writeBackup(uri: Uri) {
        val a = JSONArray(); giveaways.forEach { a.put(it.toJson()) }
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(a.toString(2)) }
        toast("Backup exported")
    }

    private fun restoreBackup(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        runCatching {
            val a = JSONArray(text); val restored = MutableList(a.length()) { i -> Giveaway.fromJson(a.getJSONObject(i)) }
            AlertDialog.Builder(this).setTitle("Restore backup").setMessage("Replace current data or merge it?")
                .setNegativeButton("Replace") { _, _ -> giveaways.clear(); giveaways += restored; persist(); showDashboard() }
                .setPositiveButton("Merge") { _, _ ->
                    restored.forEach { incoming -> if (giveaways.none { it.id == incoming.id }) giveaways += incoming }
                    persist(); showDashboard()
                }.show()
        }.onFailure { toast("That file is not a valid backup") }
    }

    private fun confirmDelete(g: Giveaway) {
        AlertDialog.Builder(this).setTitle("Delete ${g.title}?").setMessage("This removes its entries and winner history.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> giveaways.remove(g); persist(); showDashboard() }.show()
    }

    private fun persist() = store.save(giveaways)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun marginTop(v: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun safeFileName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "giveaway" }
    private fun dateFmt(ts: Long) = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(ts))

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentId != null) showDashboard() else super.onBackPressed()
    }
}
