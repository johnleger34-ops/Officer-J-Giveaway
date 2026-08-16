package com.officerj.autospa.giveaway

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var store: GiveawayStore
    private lateinit var settingsStore: AppSettings
    private lateinit var engagementStore: EngagementStore
    private val giveaways = mutableListOf<Giveaway>()
    private val engagementScans = mutableMapOf<String, EngagementScan>()
    private var currentId: String? = null
    private var pendingImportId: String? = null
    private var pendingExportId: String? = null
    private var pendingDiagnosticLog: String = ""

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
    private val createDiagnosticLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && pendingDiagnosticLog.isNotBlank()) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingDiagnosticLog) }
            }.onSuccess { toast("Diagnostic log saved") }
             .onFailure { toast("Could not save log: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GiveawayStore(this)
        settingsStore = AppSettings(this)
        engagementStore = EngagementStore(this)
        giveaways += store.load()
        engagementScans += engagementStore.load()
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

        val facebookTools = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "FACEBOOK ENTRY CHECK", 18f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Check reactions and comments independently. Missing Facebook data is marked unavailable and never cancels successful results.", 13f, Ui.MUTED))
            addView(Ui.button(this@MainActivity, "OPEN ENTRY CHECK").apply { setOnClickListener { showEngagementHome() } }, marginTop(10))
        }
        body.addView(facebookTools, marginTop(14))

        body.addView(Ui.button(this, "SETTINGS", true).apply { setOnClickListener { showSettings() } }, marginTop(10))

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
            else {
                val weightedWinner = selectWeightedTicket(g)
                if (visual is WheelView) visual.spin(weightedWinner) { selected -> onWinner(g, selected) }
                else if (visual is RaffleView) visual.drawWinner(weightedWinner) { selected -> onWinner(g, selected) }
            }
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
        ioRow.addView(Ui.button(this, "IMPORT FILE", true).apply { setOnClickListener { pendingImportId = g.id; openText.launch(arrayOf("text/plain", "text/csv", "application/json")) } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
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
        val label = Ui.text(this, "${e.name}\nEntries #$start${if (end > start) "–$end" else ""} (${e.quantity}${if (e.bonusWeight > 0.0) " + ${"%.2f".format(e.bonusWeight)} bonus" else ""})", 14f, Ui.SILVER, true)
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
        val csv = line.split(",", limit = 2)
        val xMatch = Regex("^(.*?)(?:\\s+[xX×](\\d+))$").matchEntire(line)
        when {
            csv.size == 2 && csv[1].trim().toIntOrNull() != null -> EntryGroup(name = csv[0].trim(), quantity = csv[1].trim().toInt().coerceIn(1,100000))
            xMatch != null -> EntryGroup(name = xMatch.groupValues[1].trim(), quantity = xMatch.groupValues[2].toInt().coerceIn(1,100000))
            else -> EntryGroup(name = line, quantity = 1)
        }
    }.filter { it.name.isNotBlank() }.toList()

    private fun selectWeightedTicket(g: Giveaway): Pair<Int, String>? {
        val tickets = g.expandedTickets()
        if (tickets.isEmpty()) return null
        val groups = g.entries.filter { it.quantity > 0 }
        val totalWeight = groups.sumOf { it.quantity.toDouble() + it.bonusWeight.coerceIn(0.0, 0.999999) }
        if (totalWeight <= 0.0) return tickets.random()
        var roll = Random.nextDouble(totalWeight)
        var chosen = groups.last()
        for (group in groups) {
            roll -= group.quantity.toDouble() + group.bonusWeight.coerceIn(0.0, 0.999999)
            if (roll < 0.0) { chosen = group; break }
        }
        val matching = tickets.filter { it.second.equals(chosen.name, ignoreCase = true) }
        return matching.randomOrNull() ?: tickets.random()
    }

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
            .setMessage("Save a five-second Officer J branded result video to your phone gallery?")
            .setNegativeButton("Later") { _, _ -> showEditor(g.id) }
            .setPositiveButton("CREATE VIDEO") { _, _ ->
                toast("Creating video. Keep the app open.")
                Thread {
                    val saved = runCatching { WinnerVideoExporter(this, g.type, g.title, result).export() }.getOrNull()
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
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return toast("Could not read file")
        val parsed = if (text.trim().startsWith("[")) {
            runCatching {
                val a = JSONArray(text); (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i); EntryGroup(name = o.optString("name"), quantity = o.optInt("quantity", 1).coerceAtLeast(1))
                }
            }.getOrElse { parseEntries(text) }
        } else parseEntries(text)
        g.entries += parsed.filter { it.name.isNotBlank() }; persist(); showEditor(g.id); toast("Imported ${parsed.sumOf { it.quantity }} entries")
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

    private fun showEngagementHome() {
        currentId = null
        val root = baseScreen("Facebook Entry Check") { showDashboard() }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(35)) }

        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "SELECT GIVEAWAY", 18f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "The Facebook scan is stored separately from wheel/raffle animation code.", 13f, Ui.MUTED))
        })

        if (giveaways.isEmpty()) {
            body.addView(Ui.text(this, "Create a wheel or raffle first.", 14f, Ui.MUTED), marginTop(12))
        } else giveaways.sortedByDescending { it.createdAt }.forEach { g ->
            val scan = engagementScans[g.id]
            body.addView(Ui.card(this).apply {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(Ui.text(this@MainActivity, g.title, 16f, Ui.SILVER, true))
                labels.addView(Ui.text(this@MainActivity, if (scan == null || scan.lastScan == 0L) "Not scanned" else "${scan.participants.size} people • ${dateFmt(scan.lastScan)}", 12f, Ui.MUTED))
                row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Ui.button(this@MainActivity, "OPEN").apply { setOnClickListener { showEngagement(g.id) } }, LinearLayout.LayoutParams(dp(90), dp(44)))
                addView(row)
            }, marginTop(10))
        }

        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); setContentView(root)
    }

    private fun showEngagement(giveawayId: String) {
        val g = giveaways.firstOrNull { it.id == giveawayId } ?: return showEngagementHome()
        val scan = engagementScans.getOrPut(g.id) { EngagementScan(postUrl = settingsStore.defaultPostUrl) }
        val root = baseScreen("Entry Verification") { showEngagementHome() }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(35)) }

        val postInput = Ui.input(this, "Facebook post URL or Graph post ID").apply { setText(scan.postUrl.ifBlank { settingsStore.defaultPostUrl }) }
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, g.title, 20f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Paste the original giveaway post. Reactions and comments are scanned separately so one failed category cannot wipe out the other.", 13f, Ui.MUTED))
            addView(postInput, marginTop(10))
            addView(Ui.button(this@MainActivity, "SCAN FACEBOOK").apply {
                setOnClickListener {
                    val url = postInput.text.toString().trim()
                    if (url.isBlank()) toast("Paste the giveaway post link first") else {
                        scan.postUrl = url; saveEngagement(); showScanLoading(g, scan)
                    }
                }
            }, marginTop(10))
        })

        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "SCAN STATUS", 17f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Post: ${scan.resolutionStatus}", 13f, statusColor(scan.resolutionStatus)))
            if (scan.postObjectId.isNotBlank()) {
                addView(Ui.text(this@MainActivity, "Resolved Graph ID: ${scan.postObjectId}", 12f, Ui.BLUE, true))
            }
            addView(Ui.text(this@MainActivity, "Object type: ${scan.objectType}", 12f, Ui.MUTED))
            addView(Ui.text(this@MainActivity, "Object check: ${scan.objectStatus}", 12f, Ui.MUTED))
            if (scan.resolvedUrl.isNotBlank() && scan.resolvedUrl != scan.postUrl) {
                addView(Ui.text(this@MainActivity, "Resolved URL: ${scan.resolvedUrl.take(110)}", 11f, Ui.MUTED))
            }
            addView(Ui.text(this@MainActivity, "Reactions: ${scan.reactionStatus}", 13f, statusColor(scan.reactionStatus)))
            addView(Ui.text(this@MainActivity, "Reaction summary: ${scan.reactionSummaryCount?.toString() ?: "Unavailable"}", 12f, Ui.MUTED))
            addView(Ui.text(this@MainActivity, "Comments: ${scan.commentStatus}", 13f, statusColor(scan.commentStatus)))
            addView(Ui.text(this@MainActivity, "Comment summary: ${scan.commentSummaryCount?.toString() ?: "Unavailable"}", 12f, Ui.MUTED))
            addView(Ui.text(this@MainActivity, "Page follow: ${scan.followerStatus}", 13f, Ui.MUTED))
        }, marginTop(12))

        if (scan.diagnosticLog.isNotBlank()) {
            body.addView(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, "FULL META COMPATIBILITY LOG", 17f, Ui.SILVER, true))
                addView(Ui.text(this@MainActivity, "The scan continues through compatible checks even when another check fails.", 12f, Ui.MUTED))
                addView(Ui.text(this@MainActivity, scan.diagnosticLog.take(7000), 10f, Ui.MUTED), marginTop(8))
                val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(Ui.button(this@MainActivity, "COPY LOG", true).apply {
                    setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Officer J Meta diagnostic", scan.diagnosticLog))
                        toast("Diagnostic log copied")
                    }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
                buttons.addView(Ui.button(this@MainActivity, "DOWNLOAD LOG").apply {
                    setOnClickListener {
                        pendingDiagnosticLog = scan.diagnosticLog
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                        createDiagnosticLog.launch("OfficerJ-Meta-Diagnostic-$stamp.txt")
                    }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
                addView(buttons, marginTop(9))
            }, marginTop(12))
        }

        val eligible = scan.participants.count { it.eligibility(settingsStore) != Eligibility.NOT_ELIGIBLE }
        val bonus = scan.participants.count { it.eligibility(settingsStore) == Eligibility.BONUS }
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "${scan.participants.size} PEOPLE FOUND", 17f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "$eligible eligible • $bonus at ${"%.2f".format(settingsStore.bonusWeight)} weight", 13f, Ui.BLUE, true))
            addView(Ui.text(this@MainActivity, "Minimum: ${settingsStore.minimumVerified} verified interactions. One verified interaction receives no entry.", 12f, Ui.MUTED))
            addView(Ui.button(this@MainActivity, "ADD ELIGIBLE TO ${if (g.type == GiveawayType.WHEEL) "WHEEL" else "RAFFLE"}").apply {
                isEnabled = eligible > 0
                setOnClickListener { addEligibleToGiveaway(g, scan) }
            }, marginTop(10))
        }, marginTop(12))

        body.addView(Ui.text(this, "PEOPLE", 18f, Ui.SILVER, true), marginTop(18))
        val manualName = Ui.input(this, "Add a person manually when Facebook withholds identity")
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "MANUAL VERIFICATION FALLBACK", 14f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Facebook can return engagement counts without exposing who performed them. Add a person here and mark only the interactions you can verify.", 12f, Ui.MUTED))
            addView(manualName, marginTop(8))
            addView(Ui.button(this@MainActivity, "ADD PERSON").apply {
                setOnClickListener {
                    val name = manualName.text.toString().trim()
                    if (name.isBlank()) return@setOnClickListener toast("Enter a name")
                    val existing = scan.participants.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (existing == null) {
                        scan.participants += EngagementParticipant(id = "manual_${System.currentTimeMillis()}", name = name, source = "Manual verification")
                        scan.lastScan = System.currentTimeMillis(); saveEngagement(); showEngagement(g.id)
                    } else toast("That person is already in this verification list")
                }
            }, marginTop(8))
        }, marginTop(8))
        if (scan.participants.isEmpty()) body.addView(Ui.text(this, "No identifiable engagement data yet. Facebook may still have returned counts above.", 14f, Ui.MUTED), marginTop(8))
        else scan.participants.sortedWith(compareByDescending<EngagementParticipant> { it.verifiedCount }.thenBy { it.name.lowercase() }).forEach { p ->
            body.addView(participantCard(g, scan, p), marginTop(8))
        }

        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); setContentView(root)
    }

    private fun showScanLoading(g: Giveaway, scan: EngagementScan) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24)); gravity = Gravity.CENTER }
        val progress = ProgressBar(this)
        box.addView(progress, LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(Ui.text(this, "Connecting to Facebook…\nLoading reactions…\nLoading comments…", 15f, Ui.SILVER, true).apply { gravity = Gravity.CENTER }, marginTop(12))
        setContentView(box)
        thread {
            val result = runCatching { MetaEngagementClient(settingsStore).scan(scan.postUrl) }.getOrElse {
                MetaEngagementClient.ScanResult("", scan.postUrl, "Scanner error: ${it.message ?: "error"}", emptyMap(), emptyMap(), "Unavailable: ${it.message ?: "error"}", "Unavailable: ${it.message ?: "error"}", "Unavailable")
            }
            mergeScanResult(scan, result)
            saveEngagement()
            runOnUiThread { showEngagement(g.id) }
        }
    }

    private fun mergeScanResult(scan: EngagementScan, result: MetaEngagementClient.ScanResult) {
        scan.postObjectId = result.postId
        scan.resolvedUrl = result.resolvedUrl
        scan.resolutionStatus = result.resolutionStatus
        scan.reactionStatus = result.reactionStatus
        scan.commentStatus = result.commentStatus
        scan.followerStatus = result.followerStatus
        scan.objectType = result.objectType
        scan.objectStatus = result.objectStatus
        scan.reactionSummaryCount = result.reactionSummaryCount
        scan.commentSummaryCount = result.commentSummaryCount
        scan.diagnosticLog = result.diagnosticLog
        val byId = scan.participants.associateBy { it.id }.toMutableMap()
        val ids = linkedSetOf<String>().apply { addAll(result.reactions.keys); addAll(result.comments.keys) }
        ids.forEach { id ->
            val existing = byId[id] ?: EngagementParticipant(id = id, name = result.reactions[id] ?: result.comments[id] ?: id)
            existing.name = result.reactions[id] ?: result.comments[id] ?: existing.name
            existing.reacted = when {
                result.reactions.containsKey(id) -> VerificationState.VERIFIED
                result.reactionStatus.startsWith("Loaded") -> VerificationState.NOT_FOUND
                else -> existing.reacted.takeIf { it == VerificationState.VERIFIED } ?: VerificationState.UNKNOWN
            }
            existing.commented = when {
                result.comments.containsKey(id) -> VerificationState.VERIFIED
                result.commentStatus.startsWith("Loaded") -> VerificationState.NOT_FOUND
                else -> existing.commented.takeIf { it == VerificationState.VERIFIED } ?: VerificationState.UNKNOWN
            }
            existing.updatedAt = System.currentTimeMillis(); byId[id] = existing
        }
        // Keep manually-reviewed people even if a later API call cannot return them.
        scan.participants.clear(); scan.participants += byId.values
        scan.lastScan = System.currentTimeMillis()
    }

    private fun participantCard(g: Giveaway, scan: EngagementScan, p: EngagementParticipant): View {
        val card = Ui.card(this, 10)
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(Ui.text(this, p.name, 15f, Ui.SILVER, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val eligibility = p.eligibility(settingsStore)
        val label = when (eligibility) {
            Eligibility.BONUS -> "3/3 • ${"%.2f".format(p.weight(settingsStore))}"
            Eligibility.STANDARD -> "${p.verifiedCount}/3 • ${"%.2f".format(p.weight(settingsStore))}"
            Eligibility.STANDARD_REVIEW -> "${p.verifiedCount}/3 • REVIEW"
            Eligibility.NOT_ELIGIBLE -> "${p.verifiedCount}/3 • NO ENTRY"
        }
        title.addView(Ui.text(this, label, 12f, if (eligibility == Eligibility.NOT_ELIGIBLE) Ui.RED else Ui.BLUE, true))
        card.addView(title)
        card.addView(Ui.text(this, "Reaction ${stateIcon(p.reacted)}   Comment ${stateIcon(p.commented)}   Follow ${stateIcon(p.followsPage)}", 13f, Ui.MUTED), marginTop(5))

        card.addView(verificationRow("REACTION", p.reacted) { value ->
            p.reacted = value; p.updatedAt = System.currentTimeMillis(); saveEngagement(); showEngagement(g.id)
        }, marginTop(8))
        card.addView(verificationRow("COMMENT", p.commented) { value ->
            p.commented = value; p.updatedAt = System.currentTimeMillis(); saveEngagement(); showEngagement(g.id)
        }, marginTop(5))
        card.addView(verificationRow("FOLLOW", p.followsPage) { value ->
            p.followsPage = value; p.updatedAt = System.currentTimeMillis(); saveEngagement(); showEngagement(g.id)
        }, marginTop(5))
        return card
    }

    private fun verificationRow(label: String, current: VerificationState, setValue: (VerificationState) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(this, label, 11f, Ui.MUTED, true), LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
        fun stateButton(text: String, state: VerificationState): View = Ui.button(this, text, current == state).apply {
            setOnClickListener { setValue(state) }
        }
        row.addView(stateButton("✓", VerificationState.VERIFIED), LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
        row.addView(stateButton("?", VerificationState.UNKNOWN), LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        row.addView(stateButton("✕", VerificationState.NOT_FOUND), LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
        return row
    }

    private fun addEligibleToGiveaway(g: Giveaway, scan: EngagementScan) {
        val candidates = scan.participants.filter { it.eligibility(settingsStore) != Eligibility.NOT_ELIGIBLE }
        if (candidates.isEmpty()) return toast("No eligible people")
        var added = 0
        candidates.forEach { p ->
            val weight = p.weight(settingsStore)
            val whole = kotlin.math.floor(weight).toInt().coerceAtLeast(1)
            val fractional = (weight - whole).coerceAtLeast(0.0)
            val existing = g.entries.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
            if (existing == null) {
                g.entries += EntryGroup(name = p.name, quantity = whole, bonusWeight = fractional)
                added++
            } else if (fractional > existing.bonusWeight) {
                existing.bonusWeight = fractional
            }
        }
        persist(); toast("Added $added new eligible people. Verified fractional bonuses were applied behind the scenes."); showEngagement(g.id)
    }

    private fun showSettings() {
        val root = baseScreen("Settings") { showDashboard() }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(35)) }

        val provider = Ui.input(this, "Data provider").apply { setText(settingsStore.provider) }
        val apiVersion = Ui.input(this, "Meta API version").apply { setText(settingsStore.metaApiVersion) }
        val pageId = Ui.input(this, "Officer J Page ID").apply { setText(settingsStore.pageId) }
        val token = Ui.input(this, "Meta access token").apply { setText(settingsStore.accessToken); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val defaultUrl = Ui.input(this, "Default giveaway post URL").apply { setText(settingsStore.defaultPostUrl) }
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "FACEBOOK / DATA PROVIDER", 18f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Page credentials are prefilled for this private app. Tap Edit here any time you need to replace them without rewriting the giveaway engine.", 12f, Ui.MUTED))
            addView(provider, marginTop(9)); addView(apiVersion, marginTop(7)); addView(pageId, marginTop(7)); addView(token, marginTop(7)); addView(defaultUrl, marginTop(7))
        })

        val minVerified = Ui.input(this, "Minimum verified (1-3)").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(settingsStore.minimumVerified.toString()) }
        val standardWeight = Ui.input(this, "Standard weight").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(settingsStore.standardWeight.toString()) }
        val bonusWeight = Ui.input(this, "3/3 bonus weight").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(settingsStore.bonusWeight.toString()) }
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "ELIGIBILITY", 18f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Current rule: 0–1 verified = no entry, 2/3 = 1.00, 3/3 = 1.15.", 12f, Ui.MUTED))
            addView(minVerified, marginTop(9)); addView(standardWeight, marginTop(7)); addView(bonusWeight, marginTop(7))
        }, marginTop(12))

        val updateUrl = Ui.input(this, "Update manifest HTTPS URL").apply { setText(settingsStore.updateManifestUrl) }
        body.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "APP UPDATES", 18f, Ui.SILVER, true))
            addView(Ui.text(this@MainActivity, "Future APKs can install over this app when package ID and signing certificate stay the same.", 12f, Ui.MUTED))
            addView(updateUrl, marginTop(9))
            addView(Ui.button(this@MainActivity, "CHECK FOR UPDATE", true).apply { setOnClickListener { checkForUpdate(updateUrl.text.toString().trim()) } }, marginTop(8))
        }, marginTop(12))

        body.addView(Ui.button(this, "SAVE SETTINGS").apply {
            setOnClickListener {
                settingsStore.provider = provider.text.toString().trim().ifBlank { "Meta Direct" }
                settingsStore.metaApiVersion = apiVersion.text.toString().trim().ifBlank { "v23.0" }
                settingsStore.pageId = pageId.text.toString().trim()
                settingsStore.accessToken = token.text.toString().trim()
                settingsStore.defaultPostUrl = defaultUrl.text.toString().trim()
                settingsStore.minimumVerified = minVerified.text.toString().toIntOrNull() ?: 2
                settingsStore.standardWeight = standardWeight.text.toString().toDoubleOrNull() ?: 1.0
                settingsStore.bonusWeight = bonusWeight.text.toString().toDoubleOrNull() ?: 1.15
                settingsStore.updateManifestUrl = updateUrl.text.toString().trim()
                toast("Settings saved")
            }
        }, marginTop(12))

        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); setContentView(root)
    }

    private fun checkForUpdate(url: String) {
        settingsStore.updateManifestUrl = url
        if (url.isBlank()) return toast("Enter the update manifest URL")
        toast("Checking for update…")
        thread {
            val result = runCatching { UpdateManager(this, settingsStore).check() }
            runOnUiThread {
                result.onSuccess { info ->
                    if (info == null) toast("No newer version found")
                    else AlertDialog.Builder(this).setTitle("Update ${info.versionName}")
                        .setMessage(info.notes.ifBlank { "Version ${info.versionName} is available." })
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download") { _, _ ->
                            runCatching { UpdateManager(this, settingsStore).download(info) }
                                .onSuccess { toast("Update downloading. Android will show it in notifications when finished.") }
                                .onFailure { toast("Download failed: ${it.message}") }
                        }.show()
                }.onFailure { toast("Update check failed: ${it.message}") }
            }
        }
    }

    private fun stateIcon(state: VerificationState) = when (state) {
        VerificationState.VERIFIED -> "✓"
        VerificationState.NOT_FOUND -> "✕"
        VerificationState.UNKNOWN -> "?"
    }

    private fun statusColor(status: String) = if (status.startsWith("Loaded")) Ui.BLUE else if (status.startsWith("Unavailable") || status.contains("missing", true)) Ui.RED else Ui.MUTED
    private fun saveEngagement() = engagementStore.save(engagementScans)

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
