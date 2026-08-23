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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.myvoiceboard.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class PecCard(
    val label: String,
    val symbol: String,
    val category: String,
    val imageUri: String? = null,
    val customId: String? = null,
    val defaultKey: String? = null
)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var updateManager: AppUpdateManager
    private val words = mutableListOf<String>()
    private val defaultCategories = listOf("Core", "Food", "Feelings", "Activities")
    private val categories = defaultCategories.toMutableList()
    private var currentCategory = "Core"
    private var coreCategoryName = "Core"
    private val customCards = mutableListOf<PecCard>()
    private val hiddenDefaultCards = mutableSetOf<String>()
    private val defaultCardOverrides = mutableMapOf<String, PecCard>()
    private val cardOrders = mutableMapOf<String, MutableList<String>>()
    private var pendingImageUri: Uri? = null
    private var pendingImageView: ImageView? = null
    private var pendingRemoveImageButton: Button? = null
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
            pendingRemoveImageButton?.visibility = View.VISIBLE
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
    ).map { card -> card.copy(defaultKey = "${card.category}\u001F${card.label}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)
        updateManager = AppUpdateManager(this)
        updateManager.start()
        loadCategories()
        loadCoreCategoryName()
        loadCustomCards()
        loadDefaultCardOverrides()
        loadHiddenDefaultCards()
        loadCardOrders()

        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 5 else 3
        binding.board.layoutManager = GridLayoutManager(this, span)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val adapter = recyclerView.adapter as? PecAdapter ?: return false
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val adapter = recyclerView.adapter as? PecAdapter ?: return
                saveCardOrder(currentCategory, adapter.currentItems())
            }
        }).attachToRecyclerView(binding.board)
        currentCategory = categories.first()
        setCategory(currentCategory)
        rebuildCategoryTabs(currentCategory)
        binding.speak.setOnClickListener { speakSentence() }
        binding.undo.setOnClickListener { if (words.isNotEmpty()) { words.removeLast(); updateSentence() } }
        binding.clear.setOnClickListener { words.clear(); updateSentence() }
        binding.quickIWant.setOnClickListener { addWord("I want") }
        binding.quickPlease.setOnClickListener { addWord("please") }
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
        binding.quickPhraseBar.visibility = if (category == coreCategoryName) View.GONE else View.VISIBLE
        val visibleCards = orderCards(category, allVisibleCards().filter { it.category == category })
        binding.board.adapter = PecAdapter(visibleCards) { card ->
            addWord(card.label)
        }
    }

    private fun addWord(word: String) {
        words += word
        updateSentence()
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "card")
    }

    private fun allVisibleCards(): List<PecCard> {
        val visibleDefaults = defaultCards
            .filterNot { defaultCardKey(it) in hiddenDefaultCards }
            .map { defaultCardOverrides[defaultCardKey(it)] ?: it }
        return visibleDefaults + customCards
    }

    private fun cardOrderKey(card: PecCard): String = when {
        card.defaultKey != null -> "default:${card.defaultKey}"
        card.customId != null -> "custom:${card.customId}"
        else -> "custom:${card.category}\u001F${card.label}"
    }

    private fun orderCards(category: String, cards: List<PecCard>): MutableList<PecCard> {
        val remaining = cards.toMutableList()
        val ordered = mutableListOf<PecCard>()
        cardOrders[category].orEmpty().forEach { savedKey ->
            val index = remaining.indexOfFirst { cardOrderKey(it) == savedKey }
            if (index >= 0) ordered += remaining.removeAt(index)
        }
        ordered += remaining
        return ordered
    }

    private fun saveCardOrder(category: String, cards: List<PecCard>) {
        cardOrders[category] = cards.map(::cardOrderKey).toMutableList()
        saveCardOrders()
    }

    private fun moveCardOrder(card: PecCard, destination: String) {
        val key = cardOrderKey(card)
        cardOrders.values.forEach { it.remove(key) }
        val destinationCards = orderCards(
            destination,
            allVisibleCards().filter { it.category == destination }
        ).map(::cardOrderKey).filterNot { it == key }.toMutableList()
        destinationCards += key
        cardOrders[destination] = destinationCards
        saveCardOrders()
    }

    private fun removeCardOrder(card: PecCard) {
        val key = cardOrderKey(card)
        cardOrders.values.forEach { it.remove(key) }
        saveCardOrders()
    }

    private fun showAddCardDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_card, null)
        val labelInput = view.findViewById<EditText>(R.id.cardLabel)
        val categorySpinner = view.findViewById<Spinner>(R.id.cardCategory)
        val imagePreview = view.findViewById<ImageView>(R.id.imagePreview)
        val chooseImage = view.findViewById<Button>(R.id.chooseImage)
        val removeImage = view.findViewById<Button>(R.id.removeImage)
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.setSelection(categories.indexOf(currentCategory).coerceAtLeast(0))
        pendingImageUri = null
        pendingImageView = imagePreview
        pendingRemoveImageButton = removeImage
        chooseImage.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        removeImage.setOnClickListener {
            pendingImageUri = null
            imagePreview.setImageDrawable(null)
            imagePreview.visibility = View.GONE
            removeImage.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Card")
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
                    imageUri = pendingImageUri?.toString(),
                    customId = UUID.randomUUID().toString()
                )
                saveCustomCards()
                setCategory(currentCategory)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            pendingImageUri = null
            pendingImageView = null
            pendingRemoveImageButton = null
        }
        dialog.show()
    }

    private fun showEditCardSelector() {
        val cards = allVisibleCards()
        if (cards.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Edit Card")
                .setMessage("There are no cards available to edit.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val cardNames = cards.map { "${it.symbol} ${it.label}  •  ${it.category}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select a card to edit")
            .setItems(cardNames) { _, which -> showEditCardDialog(cards[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditCardDialog(card: PecCard) {
        val view = layoutInflater.inflate(R.layout.dialog_add_card, null)
        val labelInput = view.findViewById<EditText>(R.id.cardLabel)
        val categorySpinner = view.findViewById<Spinner>(R.id.cardCategory)
        val imagePreview = view.findViewById<ImageView>(R.id.imagePreview)
        val chooseImage = view.findViewById<Button>(R.id.chooseImage)
        val removeImage = view.findViewById<Button>(R.id.removeImage)

        labelInput.setText(card.label)
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.setSelection(categories.indexOf(card.category).coerceAtLeast(0))
        pendingImageUri = card.imageUri?.let { Uri.parse(it) }
        pendingImageView = imagePreview
        pendingRemoveImageButton = removeImage
        if (card.imageUri != null) {
            imagePreview.setImageURI(pendingImageUri)
            imagePreview.visibility = View.VISIBLE
            removeImage.visibility = View.VISIBLE
            chooseImage.text = "Choose different image"
        }
        chooseImage.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        removeImage.setOnClickListener {
            pendingImageUri = null
            imagePreview.setImageDrawable(null)
            imagePreview.visibility = View.GONE
            removeImage.visibility = View.GONE
            chooseImage.text = "Choose image from phone"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Card")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = labelInput.text.toString().trim()
                if (label.isEmpty()) {
                    labelInput.error = "Enter a label"
                    return@setOnClickListener
                }
                val updated = card.copy(
                    label = label,
                    category = categorySpinner.selectedItem.toString(),
                    imageUri = pendingImageUri?.toString()
                )
                if (card.defaultKey != null) {
                    defaultCardOverrides[card.defaultKey] = updated
                    saveDefaultCardOverrides()
                } else {
                    val index = customCards.indexOfFirst { it.customId == card.customId }
                    if (index >= 0) customCards[index] = updated
                    saveCustomCards()
                }
                if (card.category != updated.category) moveCardOrder(updated, updated.category)
                setCategory(currentCategory)
                dialog.dismiss()
                Toast.makeText(this, "$label updated", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setOnDismissListener {
            pendingImageUri = null
            pendingImageView = null
            pendingRemoveImageButton = null
        }
        dialog.show()
    }

    private fun showEditCardMenu() {
        val actions = arrayOf("Add card", "Edit card", "Remove card")
        AlertDialog.Builder(this)
            .setTitle("Edit Card")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showAddCardDialog()
                    1 -> showEditCardSelector()
                    2 -> showRemoveCardDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val version = view.findViewById<TextView>(R.id.installedVersion)
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val editCard = view.findViewById<Button>(R.id.editCardFromSettings)
        val editCategory = view.findViewById<Button>(R.id.editCategoryFromSettings)
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
        editCard.setOnClickListener {
            dialog.dismiss()
            showEditCardMenu()
        }
        editCategory.setOnClickListener {
            dialog.dismiss()
            showEditCategoryDialog()
        }
        restoreDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restore default cards?")
                .setMessage("All built-in cards that were removed will return to the board.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ ->
                    hiddenDefaultCards.clear()
                    defaultCards.map { defaultCardOverrides[defaultCardKey(it)] ?: it }
                        .map { it.category }
                        .filterNot { it in categories }
                        .forEach { categories += it }
                    saveHiddenDefaultCards()
                    saveCategories()
                    if (currentCategory !in categories) currentCategory = categories.first()
                    rebuildCategoryTabs(currentCategory)
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

    private fun showEditCategoryDialog() {
        val actions = arrayOf("Create category", "Rename category", "Remove category")
        AlertDialog.Builder(this)
            .setTitle("Edit Category")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showCreateCategoryDialog()
                    1 -> showRenameCategorySelector()
                    2 -> showRemoveCategorySelector()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                        saveCategories()
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

    private fun showRenameCategorySelector() {
        AlertDialog.Builder(this)
            .setTitle("Select a category to rename")
            .setItems(categories.toTypedArray()) { _, which -> showRenameCategoryDialog(categories[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameCategoryDialog(category: String) {
        val view = layoutInflater.inflate(R.layout.dialog_add_category, null)
        val nameInput = view.findViewById<EditText>(R.id.categoryName)
        nameInput.setText(category)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename category")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                when {
                    name.isEmpty() -> nameInput.error = "Enter a category name"
                    categories.any { it != category && it.equals(name, ignoreCase = true) } ->
                        nameInput.error = "That category already exists"
                    else -> {
                        val categoryIndex = categories.indexOf(category)
                        categories[categoryIndex] = name
                        for (index in customCards.indices) {
                            if (customCards[index].category == category) {
                                customCards[index] = customCards[index].copy(category = name)
                            }
                        }
                        defaultCards.forEach { defaultCard ->
                            val key = defaultCardKey(defaultCard)
                            val effectiveCard = defaultCardOverrides[key] ?: defaultCard
                            if (effectiveCard.category == category) {
                                defaultCardOverrides[key] = effectiveCard.copy(category = name)
                            }
                        }
                        if (currentCategory == category) currentCategory = name
                        if (coreCategoryName == category) coreCategoryName = name
                        cardOrders.remove(category)?.let { cardOrders[name] = it }
                        saveCategories()
                        saveCoreCategoryName()
                        saveCustomCards()
                        saveDefaultCardOverrides()
                        saveCardOrders()
                        rebuildCategoryTabs(currentCategory)
                        setCategory(currentCategory)
                        dialog.dismiss()
                        Toast.makeText(this, "$category renamed to $name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showRemoveCategorySelector() {
        if (categories.size == 1) {
            Toast.makeText(this, "At least one category is required", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Select a category to remove")
            .setItems(categories.toTypedArray()) { _, which -> confirmCategoryRemoval(categories[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmCategoryRemoval(category: String) {
        val destination = categories.first { it != category }
        AlertDialog.Builder(this)
            .setTitle("Remove $category?")
            .setMessage("The tab will be removed. Its cards will be moved to $destination so no cards or images are lost.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val movedKeys = orderCards(
                    category,
                    allVisibleCards().filter { it.category == category }
                ).map(::cardOrderKey)
                val destinationKeys = orderCards(
                    destination,
                    allVisibleCards().filter { it.category == destination }
                ).map(::cardOrderKey)
                for (index in customCards.indices) {
                    if (customCards[index].category == category) {
                        customCards[index] = customCards[index].copy(category = destination)
                    }
                }
                defaultCards.forEach { defaultCard ->
                    val key = defaultCardKey(defaultCard)
                    val effectiveCard = defaultCardOverrides[key] ?: defaultCard
                    if (effectiveCard.category == category) {
                        defaultCardOverrides[key] = effectiveCard.copy(category = destination)
                    }
                }
                categories.remove(category)
                if (currentCategory == category) currentCategory = categories.first()
                if (coreCategoryName == category) coreCategoryName = destination
                cardOrders.remove(category)
                cardOrders[destination] = (destinationKeys + movedKeys).distinct().toMutableList()
                saveCategories()
                saveCoreCategoryName()
                saveCustomCards()
                saveDefaultCardOverrides()
                saveCardOrders()
                rebuildCategoryTabs(currentCategory)
                setCategory(currentCategory)
                Toast.makeText(this, "$category removed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRemoveCardDialog() {
        val removableCards = allVisibleCards()
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
                if (card.defaultKey != null) {
                    hiddenDefaultCards += defaultCardKey(card)
                    saveHiddenDefaultCards()
                } else {
                    customCards.removeAll { it.customId == card.customId }
                    saveCustomCards()
                }
                removeCardOrder(card)
                setCategory(currentCategory)
                Toast.makeText(this, "${card.label} removed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun defaultCardKey(card: PecCard) = card.defaultKey ?: "${card.category}\u001F${card.label}"

    private fun saveCategories() {
        val json = JSONArray()
        categories.forEach { json.put(it) }
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("categories_v2", json.toString())
            .remove("custom_categories")
            .apply()
    }

    private fun loadCategories() {
        val preferences = getSharedPreferences("pec_board", MODE_PRIVATE)
        val stored = preferences.getString("categories_v2", null)
        if (stored != null) {
            try {
                val loaded = mutableListOf<String>()
                val json = JSONArray(stored)
                for (index in 0 until json.length()) {
                    val name = json.getString(index).trim()
                    if (name.isNotEmpty() && loaded.none { it.equals(name, ignoreCase = true) }) {
                        loaded += name
                    }
                }
                if (loaded.isNotEmpty()) {
                    categories.clear()
                    categories += loaded
                }
            } catch (_: Exception) {
                categories.clear()
                categories += defaultCategories
            }
            return
        }

        val legacy = preferences.getString("custom_categories", null) ?: return
        try {
            val json = JSONArray(legacy)
            for (index in 0 until json.length()) {
                val name = json.getString(index).trim()
                if (name.isNotEmpty() && categories.none { it.equals(name, ignoreCase = true) }) {
                    categories += name
                }
            }
            saveCategories()
        } catch (_: Exception) {
            categories.clear()
            categories += defaultCategories
        }
    }

    private fun saveCoreCategoryName() {
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("core_category_name", coreCategoryName)
            .apply()
    }

    private fun loadCoreCategoryName() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getString("core_category_name", "Core") ?: "Core"
        coreCategoryName = when {
            stored in categories -> stored
            "Core" in categories -> "Core"
            else -> categories.first()
        }
    }

    private fun saveCustomCards() {
        val json = JSONArray()
        customCards.forEach { card ->
            json.put(JSONObject().apply {
                put("id", card.customId)
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
            var migrated = false
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val category = item.getString("category")
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                if (!item.has("id")) migrated = true
                if (category !in categories) {
                    categories += category
                    migrated = true
                }
                customCards += PecCard(
                    label = item.getString("label"),
                    symbol = "⭐",
                    category = category,
                    imageUri = if (item.isNull("imageUri")) null else item.getString("imageUri"),
                    customId = id
                )
            }
            if (migrated) {
                saveCustomCards()
                saveCategories()
            }
        } catch (_: Exception) {
            customCards.clear()
        }
    }

    private fun saveDefaultCardOverrides() {
        val json = JSONArray()
        defaultCardOverrides.forEach { (key, card) ->
            json.put(JSONObject().apply {
                put("key", key)
                put("label", card.label)
                put("category", card.category)
                put("imageUri", card.imageUri ?: JSONObject.NULL)
            })
        }
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("default_card_overrides", json.toString())
            .apply()
    }

    private fun loadDefaultCardOverrides() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getString("default_card_overrides", null) ?: return
        try {
            val json = JSONArray(stored)
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val key = item.getString("key")
                val baseCard = defaultCards.firstOrNull { defaultCardKey(it) == key } ?: continue
                val category = item.getString("category")
                defaultCardOverrides[key] = baseCard.copy(
                    label = item.getString("label"),
                    category = category,
                    imageUri = if (item.isNull("imageUri")) null else item.getString("imageUri")
                )
            }
        } catch (_: Exception) {
            defaultCardOverrides.clear()
        }
    }

    private fun saveCardOrders() {
        val json = JSONObject()
        cardOrders.forEach { (category, keys) ->
            json.put(category, JSONArray(keys))
        }
        getSharedPreferences("pec_board", MODE_PRIVATE).edit()
            .putString("card_orders", json.toString())
            .apply()
    }

    private fun loadCardOrders() {
        val stored = getSharedPreferences("pec_board", MODE_PRIVATE)
            .getString("card_orders", null) ?: return
        try {
            val json = JSONObject(stored)
            val categories = json.keys()
            while (categories.hasNext()) {
                val category = categories.next()
                val keys = json.getJSONArray(category)
                cardOrders[category] = MutableList(keys.length()) { index -> keys.getString(index) }
            }
        } catch (_: Exception) {
            cardOrders.clear()
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

class PecAdapter(private val items: MutableList<PecCard>, private val onClick: (PecCard) -> Unit) :
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

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in items.indices || toPosition !in items.indices) return
        val movedCard = items.removeAt(fromPosition)
        items.add(toPosition, movedCard)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun currentItems(): List<PecCard> = items.toList()

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
