package com.armsx2.ui.memorycards

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import com.armsx2.MemoryCardBackup
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.config.ConfigStore
import com.armsx2.config.SettingsScope
import java.io.File
import kr.co.iefriends.pcsx2.NativeApp

data class MemoryCardItem(
    val file: File,
    val slot1: Boolean,
    val slot2: Boolean,
    val size: Long,
)

data class MemoryCardUiState(
    val cards: List<MemoryCardItem> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

class MemoryCardViewModel(application: Application) : AndroidViewModel(application) {
    var state = androidx.compose.runtime.mutableStateOf(MemoryCardUiState())
        private set

    fun refresh() {
        val settings = ConfigStore.loadGlobal()
        val cards = cardDirectory().apply { mkdirs() }
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory || it.extension.equals("ps2", true) }
            .map { file ->
                MemoryCardItem(
                    file = file,
                    slot1 = settings.system.memoryCardSlot1Enabled && file.name.equals(settings.system.memoryCardSlot1Filename, true),
                    slot2 = settings.system.memoryCardSlot2Enabled && file.name.equals(settings.system.memoryCardSlot2Filename, true),
                    size = if (file.isFile) file.length() else directorySize(file),
                )
            }
            .sortedWith(compareByDescending<MemoryCardItem> { it.slot1 || it.slot2 }.thenBy { it.file.name.lowercase() })
        state.value = state.value.copy(cards = cards)
    }

    fun create(name: String, type: Int, sizeType: Int) {
        if (!MainActivityRuntime.nativeReady.value) {
            state.value = state.value.copy(error = "The emulator core is still starting.")
            return
        }
        val safe = name.trim().replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "MemoryCard" }
        if (safe.all { it == '.' }) {
            state.value = state.value.copy(error = "Unable to create $safe.")
            return
        }
        val cardName = if (type == 2 || safe.endsWith(".ps2", true)) safe else "$safe.ps2"
        if (File(cardDirectory(), cardName).exists() ||
            cardDirectory().listFiles().orEmpty().any { it.name.equals(cardName, true) }) {
            state.value = state.value.copy(error = "A memory card named $cardName already exists.")
            return
        }
        val success = if (type == 2) {
            // FOLDER card = a directory holding an empty `_pcsx2_superblock` marker (the core
            // identifies a folder card by that marker). Build it with the JAVA file API, NOT
            // the native path: the libc fopen the core's FileMcd_CreateNewCard uses to write
            // the superblock is DENIED on the FUSE-backed memcard storage (the same denial the
            // folder mkdir hit — and only CreateDirectoryPath got a Java fallback, file writes
            // didn't), so the native path leaves an empty, invalid 0-byte folder. Java file ops
            // work here. No .ps2 suffix; size is ignored for folder cards.
            runCatching {
                val dir = File(cardDirectory(), safe).apply { mkdirs() }
                File(dir, "_pcsx2_superblock").createNewFile()
                dir.isDirectory
            }.getOrDefault(false)
        } else {
            runCatching { NativeApp.createMemoryCard(cardName, 1, sizeType.coerceIn(1, 4)) }.getOrDefault(false)
        }
        state.value = if (success) state.value.copy(message = "Created $cardName.") else state.value.copy(error = "Unable to create $cardName.")
        refresh()
    }

    /**
     * "Choose card file" for [slot]: use a .ps2 card from anywhere on the device.
     *
     * With full storage access (granted in setup) the card is used IN PLACE: the slot stores the
     * file's full path, the core opens it directly (Pcsx2Config::FullpathToMcd), and saves go into
     * that file. Without it (or for a provider with no real path, such as a cloud drive) Android
     * only hands us a stream, so the card is copied into the cards folder and that copy assigned.
     * [serial] non-null = set it for that game only, like the per-card slot buttons.
     */
    fun chooseCardFile(slot: Int, uri: Uri, serial: String?) {
        val context = getApplication<Application>()
        val displayName = DocumentFile.fromSingleUri(context, uri)?.name?.ifBlank { null } ?: "card.ps2"
        val inPlace = devicePathFor(uri)?.takeIf { looksLikeFileCard(it) }
        if (inPlace != null) {
            if (serial != null) assignNameToGame(serial, slot, inPlace.absolutePath, displayName)
            else assignName(slot, inPlace.absolutePath, displayName)
            return
        }
        // Fallback: copy in, then assign the copy. Reuses import() so .zip folder cards work too.
        val before = cardDirectory().listFiles().orEmpty().map { it.name }.toSet()
        import(uri)
        val added = cardDirectory().listFiles().orEmpty().firstOrNull { it.name !in before } ?: return
        val note = " (copied into the app's cards folder: allow full storage access to use cards in place)"
        if (serial != null) assignNameToGame(serial, slot, added.name, added.name + note)
        else assignName(slot, added.name, added.name + note)
    }

    /** The slot's card when it is used in place (a full path), else null. */
    fun externalCard(slot: Int, serial: String?): String? {
        val name = if (serial != null) perGameCard(serial, slot) else {
            val sys = ConfigStore.loadGlobal().system
            if (slot == 1) sys.memoryCardSlot1Filename.takeIf { sys.memoryCardSlot1Enabled }
            else sys.memoryCardSlot2Filename.takeIf { sys.memoryCardSlot2Enabled }
        }
        return name?.takeIf { it.startsWith("/") }
    }

    /** Stop using an in-place card: slot goes back to the app's default card for that slot. */
    fun clearExternalCard(slot: Int, serial: String?) {
        if (serial != null) clearGameCard(serial, slot)
        else assignName(slot, if (slot == 1) "mcd001.ps2" else "mcd002.ps2", if (slot == 1) "mcd001.ps2" else "mcd002.ps2")
    }

    /** A real, readable and writable file path for a document picked from on-device storage, or
     *  null. Only the system storage provider maps to paths ("primary:Download/x.ps2" or
     *  "1234-ABCD:EQOA/x.ps2" for an SD card); cloud and app providers do not. */
    private fun devicePathFor(uri: Uri): File? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = android.provider.DocumentsContract.getDocumentId(uri)
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        if (volume.isEmpty() || relative.isEmpty()) return null
        val root = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory() else File("/storage/$volume")
        File(root, relative).takeIf { it.isFile && it.canRead() && it.canWrite() }
    }.getOrNull()

    /** File cards are whole pages: 512-byte pages, or 528 with ECC (an 8 MB card is 8,650,752). */
    private fun looksLikeFileCard(file: File): Boolean {
        val n = file.length()
        return n >= 512L * 1024L && (n % 528L == 0L || n % 512L == 0L)
    }

    fun import(uri: Uri) {
        val context = getApplication<Application>()
        val name = DocumentFile.fromSingleUri(context, uri)?.name?.ifBlank { null } ?: "Imported.ps2"

        // A FOLDER memory card is a directory of save folders plus a "_pcsx2_superblock"
        // marker, so it cannot travel as a single file — people share them zipped. The old
        // path appended ".ps2" to whatever was picked and copied the bytes verbatim, so a
        // zip landed as "card.zip.ps2" full of zip data: the core read it as an unformatted
        // file card, and formatting it then failed too. Unpack instead, into a real folder
        // card. (Directory picking is handled by importFolder below — OpenDocument() can
        // only return files.)
        if (name.endsWith(".zip", true)) {
            importZip(uri, name)
            return
        }

        val requested = if (name.endsWith(".ps2", true)) name else "$name.ps2"
        val target = uniqueFile(cardDirectory(), requested)
        val success = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                ?: error("Unable to read the selected file.")
            target.length() > 0L && NativeApp.isMemoryCard(target.name)
        }.getOrDefault(false)
        if (!success) target.delete()
        state.value = if (success) state.value.copy(message = "Imported ${target.name}.") else state.value.copy(error = "Memory card import failed.")
        refresh()
    }

    /** Unpack a zipped folder memory card into cards/<name>/. Tolerates the two common
     *  shapes: entries at the archive root, or nested under a single top-level directory. */
    private fun importZip(uri: Uri, zipName: String) {
        val context = getApplication<Application>()
        val target = uniqueFile(cardDirectory(), zipName.substringBeforeLast('.'))
        val ok = runCatching {
            val archive = File.createTempFile("memorycard-", ".zip", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use(input::copyTo)
                } ?: error("Unable to read the selected file.")
                val wrapper = findFolderCardWrapper(archive)
                extractFolderCard(archive, target, wrapper)
                // A folder card is only valid with its superblock; without it the core would
                // report "unformatted" exactly as before, so fail loudly here instead.
                File(target, "_pcsx2_superblock").isFile
            } finally {
                archive.delete()
            }
        }.getOrDefault(false)
        if (!ok) target.deleteRecursively()
        state.value = if (ok) {
            state.value.copy(message = "Imported folder card ${target.name}.")
        } else {
            state.value.copy(error = "That zip isn't a folder memory card (no _pcsx2_superblock inside).")
        }
        refresh()
    }

    private fun findFolderCardWrapper(archive: File): String? {
        val fileNames = mutableListOf<String>()
        archive.inputStream().use { raw ->
            java.util.zip.ZipInputStream(raw.buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val rel = entry.name.replace('\\', '/').trimStart('/')
                    if (!entry.isDirectory && !rel.startsWith("__MACOSX/")) {
                        fileNames.add(rel)
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        if ("_pcsx2_superblock" in fileNames) return null
        // One top-level directory holding the superblock is the card; stray top-level files
        // beside it (a README, .DS_Store) are skipped on extract rather than failing the import.
        return fileNames.filter {
            '/' in it && it.substringAfter('/') == "_pcsx2_superblock"
        }.map { it.substringBefore('/') }.singleOrNull()
    }

    private fun extractFolderCard(archive: File, target: File, wrapper: String?) {
        target.mkdirs()
        archive.inputStream().use { raw ->
            java.util.zip.ZipInputStream(raw.buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val rel = entry.name.replace('\\', '/').trimStart('/')
                    val outside = wrapper != null && !rel.startsWith("$wrapper/")
                    val stripped = if (wrapper != null) rel.substringAfter('/') else rel
                    if (!outside && stripped.isNotBlank() && !rel.startsWith("__MACOSX/")) {
                        val out = File(target, stripped)
                        // Zip-slip guard: never write outside the card directory.
                        if (out.canonicalPath.startsWith(target.canonicalPath + File.separator) ||
                            out.canonicalPath == target.canonicalPath) {
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zin.copyTo(it) }
                            }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
    }

    /** Import a folder memory card straight from a directory the user picks. */
    fun importFolder(uri: Uri) {
        val context = getApplication<Application>()
        val source = DocumentFile.fromTreeUri(context, uri)
        if (source == null || !source.isDirectory) {
            state.value = state.value.copy(error = "Unable to open the selected folder.")
            return
        }
        val target = uniqueFile(cardDirectory(), source.name?.ifBlank { null } ?: "Imported")
        val ok = runCatching {
            target.mkdirs()
            copyTree(source, target)
            File(target, "_pcsx2_superblock").isFile
        }.getOrDefault(false)
        if (!ok) target.deleteRecursively()
        state.value = if (ok) {
            state.value.copy(message = "Imported folder card ${target.name}.")
        } else {
            state.value.copy(error = "That folder isn't a memory card (no _pcsx2_superblock inside).")
        }
        refresh()
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        source.listFiles().forEach { child ->
            val childName = child.name ?: return@forEach
            if (child.isDirectory) {
                copyTree(child, File(destination, childName).apply { mkdirs() })
            } else if (child.isFile) {
                getApplication<Application>().contentResolver.openInputStream(child.uri)?.use { input ->
                    File(destination, childName).outputStream().use(input::copyTo)
                }
            }
        }
    }

    fun assign(slot: Int, item: MemoryCardItem) = assignName(slot, item.file.name, item.file.name)

    /** Point [slot] at [name]: a card in the cards folder (bare file name) or, for a card used in
     *  place elsewhere on the device, its full path. [label] is what the message calls it. */
    private fun assignName(slot: Int, name: String, label: String) {
        if (!MainActivityRuntime.nativeReady.value) {
            state.value = state.value.copy(error = "The emulator core is still starting.")
            return
        }
        val success = runCatching {
            NativeApp.setSetting("MemoryCards", "Slot${slot}_Enable", "bool", "false")
            NativeApp.setSetting("MemoryCards", "Slot${slot}_Filename", "string", name)
            NativeApp.setSetting("MemoryCards", "Slot${slot}_Enable", "bool", "true")
            NativeApp.commitSettings()
            val current = ConfigStore.loadGlobal()
            ConfigStore.saveGlobal(
                if (slot == 1) current.copy(system = current.system.copy(memoryCardSlot1Enabled = true, memoryCardSlot1Filename = name))
                else current.copy(system = current.system.copy(memoryCardSlot2Enabled = true, memoryCardSlot2Filename = name)),
            )
            true
        }.getOrDefault(false)
        state.value = if (success) state.value.copy(message = "$label assigned to slot $slot.") else state.value.copy(error = "Unable to change slot $slot.")
        refresh()
    }

    /**
     * Per-game memory card (#137, NetherSX2-style): store the card as a per-game
     * override in the `config.game.<serial>` tier. MainActivityRuntime.applyRendererPrefs
     * already resolves and applies this at every boot, so no native call is needed —
     * it takes effect the next time the game starts.
     */
    fun assignToGame(serial: String, slot: Int, item: MemoryCardItem) =
        assignNameToGame(serial, slot, item.file.name, item.file.name)

    private fun assignNameToGame(serial: String, slot: Int, name: String, label: String) {
        val resolved = ConfigStore.resolveForGame(serial)
        val updated = if (slot == 1) {
            resolved.copy(system = resolved.system.copy(memoryCardSlot1Enabled = true, memoryCardSlot1Filename = name))
        } else {
            resolved.copy(system = resolved.system.copy(memoryCardSlot2Enabled = true, memoryCardSlot2Filename = name))
        }
        val ok = runCatching { ConfigStore.save(SettingsScope.Game, serial, updated) }.isSuccess
        state.value = if (ok) {
            state.value.copy(message = "$label set for this game (slot $slot). Restart the game to apply.")
        } else {
            state.value.copy(error = "Unable to set a per-game card.")
        }
        refresh()
    }

    /**
     * Drop this game's per-game card for [slot] so it falls back to the global choice.
     *
     * There was previously no way to undo a per-game assignment at all. ConfigStore.save
     * stores a DIFF against global, so writing the global value back makes the key vanish
     * from the override blob rather than pinning it to the same value.
     */
    fun clearGameCard(serial: String, slot: Int) {
        val global = ConfigStore.loadGlobal()
        val resolved = ConfigStore.resolveForGame(serial)
        val updated = if (slot == 1) {
            resolved.copy(
                system = resolved.system.copy(
                    memoryCardSlot1Enabled = global.system.memoryCardSlot1Enabled,
                    memoryCardSlot1Filename = global.system.memoryCardSlot1Filename,
                ),
            )
        } else {
            resolved.copy(
                system = resolved.system.copy(
                    memoryCardSlot2Enabled = global.system.memoryCardSlot2Enabled,
                    memoryCardSlot2Filename = global.system.memoryCardSlot2Filename,
                ),
            )
        }
        val ok = runCatching { ConfigStore.save(SettingsScope.Game, serial, updated) }.isSuccess
        state.value = if (ok) {
            state.value.copy(message = "Slot $slot follows the global card again. Restart the game to apply.")
        } else {
            state.value.copy(error = "Unable to clear the per-game card.")
        }
        refresh()
    }

    /** The card filename this game currently resolves to (for the "in use" chip). */
    fun perGameCard(serial: String?, slot: Int): String? {
        serial ?: return null
        val resolved = runCatching { ConfigStore.resolveForGame(serial) }.getOrNull() ?: return null
        return if (slot == 1) {
            resolved.system.memoryCardSlot1Filename.takeIf { resolved.system.memoryCardSlot1Enabled }
        } else {
            resolved.system.memoryCardSlot2Filename.takeIf { resolved.system.memoryCardSlot2Enabled }
        }
    }

    fun delete(item: MemoryCardItem) {
        if (item.slot1 || item.slot2) {
            state.value = state.value.copy(error = "Assign another card before deleting an active card.")
            return
        }
        val deleted = if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
        state.value = if (deleted) state.value.copy(message = "Deleted ${item.file.name}.") else state.value.copy(error = "Unable to delete ${item.file.name}.")
        refresh()
    }

    /** Copy a (file) memory card out to a user-chosen SAF location for backup / moving to another
     *  device. Folder cards aren't a single file, so the UI only offers Export for file cards. */
    fun export(src: File, uri: Uri) {
        val ok = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("Unable to open destination.")
        }.isSuccess
        state.value = if (ok) state.value.copy(message = "Exported ${src.name}.")
                      else state.value.copy(error = "Export failed for ${src.name}.")
    }

    // ---- backups ---------------------------------------------------------------------------

    /** Snapshots of [card], newest first. */
    fun backups(card: File): List<MemoryCardBackup.Snapshot> =
        runCatching { MemoryCardBackup.list(getApplication<Application>(), card.name) }.getOrDefault(emptyList())

    /** Take a copy of [card] now. Blocking; callers dispatch it off the UI thread. */
    fun backupNow(card: File) {
        if (NativeApp.hasActiveVM()) {
            state.value = state.value.copy(error = "Stop the game before backing up a memory card.")
            return
        }
        when (MemoryCardBackup.verify(card)) {
            MemoryCardBackup.Health.MISSING ->
                state.value = state.value.copy(error = "${card.name} is not there to back up.")
            MemoryCardBackup.Health.UNREADABLE ->
                // Refusing is the point: a copy of a broken card is worth nothing and costs a
                // rotation slot that may hold the last good one.
                state.value = state.value.copy(
                    error = "${card.name} cannot be read, so there is nothing worth backing up. " +
                        "Restore an earlier backup instead.",
                )
            MemoryCardBackup.Health.GOOD -> {
                val snap = MemoryCardBackup.snapshot(
                    getApplication<Application>(), card, MemoryCardBackup.Reason.MANUAL, game = null,
                )
                if (snap != null) MemoryCardBackup.prune(getApplication<Application>(), card.name)
                state.value = if (snap != null) {
                    state.value.copy(message = "Backed up ${card.name}.")
                } else {
                    state.value.copy(error = "Could not back up ${card.name}.")
                }
            }
        }
    }

    /**
     * Put [snap] back over the live card. Blocking; callers dispatch it off the UI thread.
     *
     * Refused while a game is running. The console keeps its own cached picture of the card's
     * directory in guest memory, so swapping the bytes underneath leaves that picture stale and
     * the next write scribbles over the restore — reopening the card does not fix it.
     */
    fun restoreBackup(snap: MemoryCardBackup.Snapshot) {
        if (NativeApp.hasActiveVM()) {
            state.value = state.value.copy(
                error = "Stop the game before restoring a memory card. The console keeps its own " +
                    "picture of the card while it runs, and would write over the restored copy.",
            )
            return
        }
        val outcome = MemoryCardBackup.restore(getApplication<Application>(), snap)
        state.value = if (outcome.ok) {
            state.value.copy(message = outcome.detail)
        } else {
            state.value.copy(error = outcome.detail)
        }
        refresh()
    }

    fun dismissMessage() {
        state.value = state.value.copy(error = null, message = null)
    }

    private fun cardDirectory(): File = File(MainActivityRuntime.assetCopyRoot(getApplication()), "memcards")

    private fun uniqueFile(directory: File, name: String): File {
        directory.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = File(directory, name)
        var index = 2
        while (target.exists()) target = File(directory, "$base-$index$extension").also { index++ }
        return target
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
}
