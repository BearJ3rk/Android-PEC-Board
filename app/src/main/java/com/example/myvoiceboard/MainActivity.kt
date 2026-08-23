package com.example.myvoiceboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myvoiceboard.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PecCard(
    val label: String,
    val symbol: String,
    val category: String,
    val imageUri: String? = null
)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var updateManager: AppUpdateManager
    private val words = mutableListOf<String>()
    private val defaultCategories = listOf("Core", "Food", "Feelings", "Activities")
    private val categories = defaultCategories.toMutableList()
    private var currentCategory = "Core"
    private val customCards = mutableListOf<PecCard>()
    private val hiddenDefaultCards = mutableSetOf<String>()
    private var pendingImageUri: Uri? = null
    private var pendingImageView: ImageView? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers grant access without a persistable permission.
            }
            pendingImageUri = uri
            pendingImageView?.apply {
                setImageURI(uri)
                visibility = View.VISIBLE
            }
        }
    }
    private val defaultCards = listOf(
        PecCard("I want", "🙋", "Core"), PecCard("I need help", "🤝", "Core"),
        PecCard("Yes", "✅", "Core"), PecCard("No", "❌", "Core"),
        PecCard("More", "➕", "Core"), PecCard("Finished", "🏁", "Core"),
        PecCard("Water", "💧", "Food"), PecCard("Snack", "🍎", "Food"),
        PecCard("Breakfast", "🥣", "Food"), PecCard("Lunch", "🥪", "Food"),
        PecCard("Happy", "😊", "Feelings"), PecCard("Sad", "😢", "Feelings"),
        PecCard("Angry", "😠", "Feelings"), PecCard("Worried", "😟", "Feelings"),
        PecCard("Quiet", "🤫", "Feelings"), PecCard("Break", "⏸️", "Activities"),
        PecCard("Bathroom", "🚻", "Activities"), PecCard("Play", "🧸", "Activities"),
        PecCard("Read", "📖", "Activities"), PecCard("Go outside", "🌳", "Activities")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)
        updateManager = AppUpdateManager(this)
        updateManager.start()
        loadCustomCategories()
        loadCustomCards()
        loadHiddenDefaultCards()

        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 5 else 3
        binding.board.layoutManager = GridLayoutManager(this, span)
        setCategory("Core")
        rebuildCategoryTabs("Core")
        binding.speak.setOnClickListener { speakSentence() }
        binding.undo.setOnClickListener { if (words.isNotEmpty()) { words.removeLast(); updateSentence() } }
        binding.clear.setOnClickListener { words.clear(); updateSentence() }
        binding.settings.setOnClickListener { showSettingsDialog() }
    }

    private fun rebuildCategoryTabs(selectedCategory: String) {
        binding.categories.removeAllViews()
        categories.forEach { name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = name == selectedCategory
                minHeight = 48
                setOnClickListener { setCategory(name) }
            }
            binding.categories.addView(chip)
        }
    }

    private fun setCategory(category: String) {
        currentCategory = category
        val visibleCards = (defaultCards.filterNot { defaultCardKey(it) in hiddenDefaultCards } + customCards)
            .filter { it.category == category }
        binding.board.adapter = PecAdapter(visibleCards) { card ->
            words += card.label
            updateSentence()
            tts.speak(card.label, TextToSpeech.QUEUE_FLUSH, null, "card")
        }
    }

    private fun showAddCardDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_card, null)
        val labelInput = view.findViewById<EditText>(R.id.cardLabel)
        val categorySpinner = view.findViewById<Spinner>(R.id.cardCategory)
        val imagePreview = view.findViewById<ImageView>(R.id.imagePreview)
        val chooseImage = view.findViewById<Button>(R.id.chooseImage)
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.setSelection(categories.indexOf(currentCategory).coerceAtLeast(0))
        pendingImageUri = null
        pendingImageView = imagePreview
        chooseImage.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add communication card")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = labelInput.text.toString().trim()
                if (label.isEmpty()) {
                    labelInput.error = "Enter a label"
                    return@setOnClickListener
                }
                customCards += PecCard(
                    label = label,
                    symbol = "⭐",
                    category = categorySpinner.selectedItem.toString(),
                    imageUri = pendingImageUri?.toString()
                )
                saveCustomCards()
                setCategory(currentCategory)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            pendingImageUri = null
            pendingImageView = null
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val version = view.findViewById<TextView>(R.id.installedVersion)
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val addCard = view.findViewById<Button>(R.id.addCardFromSettings)
        val createCategory = view.findViewById<Button>(R.id.createCategoryFromSettings)
        val removeCard = view.findViewById<Button>(R.id.removeCardFromSettings)
        val restoreDefaults = view.findViewById<Button>(R.id.restoreDefaultCards)
        val check = view.findViewById<Button>(R.id.checkUpdates)
        val download = view.findViewById<Button>(R.id.downloadUpdate)
        version.text = "Installed version: ${updateManager.currentVersion()}"
        restoreDefaults.visibility = if (hiddenDefaultCards.isEmpty()) View.GONE else View.VISIBLE
        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setNegativeButton("Close", null)
            .create()
        addCard.setOnClickListener {
            dialog.dismiss()
            showAddCardDialog()
        }
        createCategory.setOnClickListener {
            dialog.dismiss()
            showCreateCategoryDialog()
        }
        removeCard.setOnClickListener {
            dialog.dismiss()
            showRemoveCardDialog()
        }
        restoreDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restore default cards?")
                .setMessage("All built-in cards that were removed will return to the board.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ ->
                    hiddenDefaultCards.clear()
                    saveHiddenDefaultCards()
                    setCategory(currentCategory)
                    restoreDefaults.visibility = View.GONE
                    Toast.makeText(this, "Default cards restored", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        check.setOnClickListener {
            check.isEnabled = false
            download.visibility = View.GONE
            progress.visibility = View.VISIBLE
            status.text = "Checking GitHub releases…"
            updateManager.checkForUpdate { message, available ->
                if (!dialog.isShowing) return@checkForUpdate
                progress.visibility = View.GONE
                check.isEnabled = true
                status.text = message
                if (available != null) {
                    download.text = "Download ${available.tag}"
                    download.visibility = View.VISIBLE
                    download.setOnClickListener {
                        download.isEnabled = false
                        updateManager.download(available) { updateStatus ->
                            status.text = updateStatus
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showCreateCategoryDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_category, null)
        val nameInput = view.findViewById<EditText>(R.id.categoryName)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create category")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                when {
                    name.isEmpty() -> nameInput.error = "Enter a category name"
                    categories.any { it.equals(name, ignoreCase = true) } ->
                        nameInput.error = "That category already exists"
                    else -> {
                        categories += name
                        saveCustomCategories()
                        setCategory(name)
                        rebuildCategoryTabs(name)
                        binding.categoryScroll.post { binding.categoryScroll.fullScroll(View.FOCUS_RIGHT) }
                        dialog.dismiss()
                        Toast.makeText(this, "$name category created", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showRemoveCardDialog() {
        val removableCards = defaultCards.filterNot { defaultCardKey(it) in hiddenDefaultCards } + customCards
        if (removableCards.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Remove card")
                .setMessage("There are no cards available to remove.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val cardNames = removableCards.map { "${it.symbol} ${it.label}  •  ${it.category}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select a card to remove")
            .setItems(cardNames) { _, which -> confirmCardRemoval(removableCards[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmCardRemoval(card: PecCard) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${card.label}?")
            .setMessage("This card will no longer appear on the board.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                if (card in defaultCards) {
                    hiddenDefaultCards += defaultCardKey(card)
                    saveHiddenDefaultCards()
                } else {
                    customCards.remove(card)
                    saveCustomCards()
                }
                setCategory(currentCategory)
                Toast.makeText(this, "${card.label} removed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun defaultCardKey(card: PecCard) = "${card.category}\u001F${card.label}"

    private fun saveCustomCategories() {
        val json = JSONArray()
        categories.drop(defaultCategories.size).forEach { json.put(it) }
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("custom_categories", json.toString())
            .apply()
    }

    private fun loadCustomCategories() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getString("custom_categories", null) ?: return
        try {
            val json = JSONArray(stored)
            for (index in 0 until json.length()) {
                val name = json.getString(index).trim()
                if (name.isNotEmpty() && categories.none { it.equals(name, ignoreCase = true) }) {
                    categories += name
                }
            }
        } catch (_: Exception) {
            categories.clear()
            categories += defaultCategories
        }
    }

    private fun saveCustomCards() {
        val json = JSONArray()
        customCards.forEach { card ->
            json.put(JSONObject().apply {
                put("label", card.label)
                put("category", card.category)
                put("imageUri", card.imageUri ?: JSONObject.NULL)
            })
        }
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("custom_cards", json.toString())
            .apply()
    }

    private fun loadCustomCards() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getString("custom_cards", null) ?: return
        try {
            val json = JSONArray(stored)
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                customCards += PecCard(
                    label = item.getString("label"),
                    symbol = "⭐",
                    category = item.getString("category"),
                    imageUri = if (item.isNull("imageUri")) null else item.getString("imageUri")
                )
            }
        } catch (_: Exception) {
            customCards.clear()
        }
    }

    private fun saveHiddenDefaultCards() {
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putStringSet("hidden_default_cards", hiddenDefaultCards.toSet())
            .apply()
    }

    private fun loadHiddenDefaultCards() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getStringSet("hidden_default_cards", emptySet()) ?: emptySet()
        hiddenDefaultCards.clear()
        hiddenDefaultCards += stored
    }

    private fun updateSentence() {
        binding.sentence.text = if (words.isEmpty()) "Tap a card to build a sentence" else words.joinToString(" ")
    }

    private fun speakSentence() {
        if (words.isNotEmpty()) tts.speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "sentence")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    override fun onDestroy() {
        updateManager.stop(); tts.stop(); tts.shutdown(); super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.installPendingUpdate()
    }
}

class PecAdapter(private val items: List<PecCard>, private val onClick: (PecCard) -> Unit) :
    RecyclerView.Adapter<PecAdapter.Holder>() {
    class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_pec, parent, false)
    ) {
        val symbol: TextView = itemView.findViewById(R.id.symbol)
        val image: ImageView = itemView.findViewById(R.id.cardImage)
        val label: TextView = itemView.findViewById(R.id.label)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent)
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.symbol.text = item.symbol
        if (item.imageUri != null) {
            holder.image.setImageURI(Uri.parse(item.imageUri))
            holder.image.visibility = View.VISIBLE
            holder.symbol.visibility = View.GONE
        } else {
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            holder.symbol.visibility = View.VISIBLE
        }
        holder.label.text = item.label
        holder.itemView.contentDescription = item.label
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
