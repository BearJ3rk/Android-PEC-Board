package com.example.myvoiceboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BoardBackupSnapshot(
    val appVersion: String,
    val categories: List<String>,
    val coreCategoryName: String,
    val customCards: List<PecCard>,
    val defaultCardOverrides: Map<String, PecCard>,
    val hiddenDefaultCards: Set<String>,
    val cardOrders: Map<String, List<String>>
)

data class BoardBackupData(
    val categories: List<String>,
    val coreCategoryName: String,
    val customCards: List<PecCard>,
    val defaultCardOverrides: Map<String, PecCard>,
    val hiddenDefaultCards: Set<String>,
    val cardOrders: Map<String, List<String>>
)

data class BackupSummary(val customizedCards: Int, val images: Int)

class BoardBackupManager(private val context: Context) {
    companion object {
        private const val BACKUP_FORMAT = "pec-board-backup"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 5L * 1024L * 1024L
        private const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
        private const val MAX_TOTAL_IMAGE_BYTES = 500L * 1024L * 1024L
        private const val MAX_CATEGORIES = 200
        private const val MAX_CARDS = 2_000
        private const val MAX_ORDER_ITEMS = 5_000
        private val IMAGE_ENTRY_PATTERN = Regex("images/[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    }

    fun exportBackup(destination: Uri, snapshot: BoardBackupSnapshot): Result<BackupSummary> = runCatching {
        val imagePaths = linkedMapOf<String, String>()
        (snapshot.customCards + snapshot.defaultCardOverrides.values).forEach { card ->
            card.imageUri?.let { uri ->
                if (uri !in imagePaths) imagePaths[uri] = "images/image-${imagePaths.size}.img"
            }
        }

        val manifest = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", snapshot.appVersion)
            put("categories", JSONArray(snapshot.categories))
            put("coreCategoryName", snapshot.coreCategoryName)
            put("customCards", JSONArray().apply {
                snapshot.customCards.forEach { card -> put(cardJson(card, imagePaths)) }
            })
            put("defaultCardOverrides", JSONArray().apply {
                snapshot.defaultCardOverrides.forEach { (key, card) ->
                    put(cardJson(card, imagePaths).put("key", key))
                }
            })
            put("hiddenDefaultCards", JSONArray(snapshot.hiddenDefaultCards.toList()))
            put("cardOrders", JSONObject().apply {
                snapshot.cardOrders.forEach { (category, keys) -> put(category, JSONArray(keys)) }
            })
        }

        val temporaryFile = File.createTempFile("pec-board-backup-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                var totalImageBytes = 0L
                imagePaths.forEach { (uriText, archivePath) ->
                    zip.putNextEntry(ZipEntry(archivePath))
                    val input = context.contentResolver.openInputStream(Uri.parse(uriText))
                        ?: throw IOException("One of the card images can no longer be opened.")
                    input.use {
                        val copied = copyWithLimit(it, zip, MAX_IMAGE_BYTES, "A card image is too large to back up.")
                        totalImageBytes += copied
                        if (totalImageBytes > MAX_TOTAL_IMAGE_BYTES) {
                            throw IOException("The card images are too large to fit in one backup.")
                        }
                    }
                    zip.closeEntry()
                }
            }

            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("The selected save location could not be opened.")
            output.use { destinationStream ->
                temporaryFile.inputStream().buffered().use { source -> source.copyTo(destinationStream) }
            }
        } finally {
            temporaryFile.delete()
        }

        BackupSummary(
            customizedCards = snapshot.customCards.size + snapshot.defaultCardOverrides.size,
            images = imagePaths.size
        )
    }

    fun importBackup(source: Uri): Result<BoardBackupData> {
        val importId = UUID.randomUUID().toString()
        var stagingDirectory: File? = null
        var permanentDirectory: File? = null

        return runCatching {
            val imageRoot = File(context.filesDir, "board_images")
            if (!imageRoot.exists() && !imageRoot.mkdirs()) {
                throw IOException("Could not prepare storage for restored images.")
            }
            val staging = File(imageRoot, ".import-$importId")
            if (!staging.mkdirs()) throw IOException("Could not prepare storage for restored images.")
            stagingDirectory = staging
            var manifestBytes: ByteArray? = null
            var totalImageBytes = 0L
            val extractedImages = mutableMapOf<String, File>()
            val seenEntries = mutableSetOf<String>()
            val sourceStream = context.contentResolver.openInputStream(source)
                ?: throw IOException("The selected backup could not be opened.")

            ZipInputStream(BufferedInputStream(sourceStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!seenEntries.add(entry.name)) throw IOException("The backup contains duplicate files.")
                    when {
                        entry.isDirectory && entry.name == "images/" -> Unit
                        entry.name == MANIFEST_ENTRY -> {
                            val output = ByteArrayOutputStream()
                            copyWithLimit(zip, output, MAX_MANIFEST_BYTES, "The backup information is too large.")
                            manifestBytes = output.toByteArray()
                        }
                        IMAGE_ENTRY_PATTERN.matches(entry.name) -> {
                            if (extractedImages.size >= MAX_CARDS) throw IOException("The backup contains too many images.")
                            val target = File(staging, entry.name.substringAfterLast('/'))
                            FileOutputStream(target).buffered().use { output ->
                                val copied = copyWithLimit(zip, output, MAX_IMAGE_BYTES, "A backup image is too large.")
                                totalImageBytes += copied
                                if (totalImageBytes > MAX_TOTAL_IMAGE_BYTES) {
                                    throw IOException("The backup images are too large to restore.")
                                }
                            }
                            extractedImages[entry.name] = target
                        }
                        else -> throw IOException("The selected file is not a valid PEC Board backup.")
                    }
                    zip.closeEntry()
                }
            }

            val manifestText = String(
                manifestBytes ?: throw IOException("The backup information is missing."),
                Charsets.UTF_8
            )
            val manifest = try {
                JSONObject(manifestText)
            } catch (_: Exception) {
                throw IOException("The backup information is damaged.")
            }
            requireValid(manifest.optString("format") == BACKUP_FORMAT, "This is not a PEC Board backup.")
            requireValid(manifest.optInt("formatVersion", -1) == FORMAT_VERSION, "This backup version is not supported.")

            val restored = parseManifest(manifest, extractedImages.keys)
            val finalDirectory = File(imageRoot, "restore-$importId")
            if (!staging.renameTo(finalDirectory)) {
                if (!staging.copyRecursively(finalDirectory, overwrite = false)) {
                    throw IOException("The restored images could not be saved.")
                }
                staging.deleteRecursively()
            }
            permanentDirectory = finalDirectory

            fun restoredImageUri(path: String?): String? = path?.let {
                Uri.fromFile(File(finalDirectory, it.substringAfterLast('/'))).toString()
            }

            BoardBackupData(
                categories = restored.categories,
                coreCategoryName = restored.coreCategoryName,
                customCards = restored.customCards.map { raw ->
                    PecCard(raw.label, "⭐", raw.category, restoredImageUri(raw.imagePath), customId = raw.id)
                },
                defaultCardOverrides = restored.defaultCards.associate { raw ->
                    raw.key!! to PecCard(
                        raw.label,
                        "",
                        raw.category,
                        restoredImageUri(raw.imagePath),
                        defaultKey = raw.key
                    )
                },
                hiddenDefaultCards = restored.hiddenDefaultCards,
                cardOrders = restored.cardOrders
            )
        }.onFailure {
            stagingDirectory?.deleteRecursively()
            permanentDirectory?.deleteRecursively()
        }
    }

    fun cleanUnusedRestoredImages(imageUris: Collection<String?>) {
        val imageRoot = File(context.filesDir, "board_images")
        val referencedFiles = imageUris.mapNotNull { uriText ->
            uriText?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme == "file" }
                ?.path
                ?.let(::File)
                ?.canonicalPath
        }.toSet()

        imageRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("restore-") }?.forEach { directory ->
            val containsReferencedImage = directory.walkTopDown()
                .filter { it.isFile }
                .any { it.canonicalPath in referencedFiles }
            if (!containsReferencedImage) directory.deleteRecursively()
        }
        imageRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(".import-") }
            ?.forEach { it.deleteRecursively() }
    }

    private data class RawCard(
        val id: String?,
        val key: String?,
        val label: String,
        val category: String,
        val imagePath: String?
    )

    private data class ParsedManifest(
        val categories: List<String>,
        val coreCategoryName: String,
        val customCards: List<RawCard>,
        val defaultCards: List<RawCard>,
        val hiddenDefaultCards: Set<String>,
        val cardOrders: Map<String, List<String>>
    )

    private fun parseManifest(manifest: JSONObject, availableImages: Set<String>): ParsedManifest {
        val categoryArray = manifest.optJSONArray("categories")
            ?: throw IOException("The backup has no categories.")
        requireValid(categoryArray.length() in 1..MAX_CATEGORIES, "The backup has an invalid number of categories.")
        val categories = mutableListOf<String>()
        for (index in 0 until categoryArray.length()) {
            val category = categoryArray.optString(index).trim()
            requireValid(category.isNotEmpty() && category.length <= 80, "The backup contains an invalid category name.")
            requireValid(categories.none { it.equals(category, ignoreCase = true) }, "The backup contains duplicate categories.")
            categories += category
        }

        val coreCategoryName = manifest.optString("coreCategoryName").trim()
        requireValid(coreCategoryName in categories, "The backup's Core category is missing.")

        fun parseCards(name: String, defaultCards: Boolean): List<RawCard> {
            val array = manifest.optJSONArray(name) ?: JSONArray()
            requireValid(array.length() <= MAX_CARDS, "The backup contains too many cards.")
            val cards = mutableListOf<RawCard>()
            val identities = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw IOException("The backup contains an invalid card.")
                val label = item.optString("label").trim()
                val category = item.optString("category").trim()
                val id = if (defaultCards) null else item.optString("id").trim().ifEmpty { UUID.randomUUID().toString() }
                val key = if (defaultCards) item.optString("key").takeIf { it.isNotBlank() } else null
                val imagePath = if (item.isNull("imagePath")) {
                    null
                } else {
                    item.optString("imagePath").takeIf { it.isNotBlank() }
                }
                requireValid(label.isNotEmpty() && label.length <= 200, "The backup contains an invalid card name.")
                requireValid(category in categories, "A card refers to a missing category.")
                requireValid(!defaultCards || key != null, "A default card is missing its identity.")
                requireValid(imagePath == null || imagePath in availableImages, "A card image is missing from the backup.")
                val identity = key ?: id!!
                requireValid(identities.add(identity), "The backup contains duplicate cards.")
                cards += RawCard(id, key, label, category, imagePath)
            }
            return cards
        }

        val hiddenArray = manifest.optJSONArray("hiddenDefaultCards") ?: JSONArray()
        requireValid(hiddenArray.length() <= MAX_CARDS, "The backup contains too many removed cards.")
        val hiddenCards = buildSet {
            for (index in 0 until hiddenArray.length()) {
                hiddenArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val orderObject = manifest.optJSONObject("cardOrders") ?: JSONObject()
        val orders = mutableMapOf<String, List<String>>()
        val orderCategories = orderObject.keys()
        while (orderCategories.hasNext()) {
            val category = orderCategories.next()
            requireValid(category in categories, "Card order refers to a missing category.")
            val keys = orderObject.optJSONArray(category) ?: throw IOException("A card order is invalid.")
            requireValid(keys.length() <= MAX_ORDER_ITEMS, "A category contains too many ordered cards.")
            orders[category] = List(keys.length()) { index -> keys.optString(index) }.filter { it.isNotBlank() }
        }

        return ParsedManifest(
            categories,
            coreCategoryName,
            parseCards("customCards", defaultCards = false),
            parseCards("defaultCardOverrides", defaultCards = true),
            hiddenCards,
            orders
        )
    }

    private fun cardJson(card: PecCard, imagePaths: Map<String, String>) = JSONObject().apply {
        card.customId?.let { put("id", it) }
        put("label", card.label)
        put("category", card.category)
        put("imagePath", card.imageUri?.let(imagePaths::get) ?: JSONObject.NULL)
    }

    private fun copyWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long,
        limitMessage: String
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > limit) throw IOException(limitMessage)
            output.write(buffer, 0, read)
        }
        return copied
    }

    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
