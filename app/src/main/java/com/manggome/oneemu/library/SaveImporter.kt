package com.manggome.oneemu.library

import com.manggome.oneemu.model.SystemId
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 세이브 가져오기: a save file someone downloaded (all characters unlocked, a finished game...) put where
 * this game's core reads it. The formats people actually share:
 *
 * - Battery saves (.sav/.srm/.dsv...) for cartridge systems and the DS: the frontend's `<rom>.srm`.
 * - GameCube: .gci, .gcs (GameShark), .sav (Datel MaxDrive) - converted to .gci and dropped into the region's
 *   GCI folder, which Dolphin reads. Dolphin only shows a save whose game code matches the disc exactly, so a
 *   save from another region's release is flagged and can be relabelled.
 * - PS2 with Play!: .psu, .max (Action Replay Max, LZARI-compressed), .xps (X-Port) - unpacked into Play!'s
 *   folder memory card, one folder per save as on a real card.
 * - PSP: a .zip holding the save folder (the one with PARAM.SFO), into PSP/SAVEDATA.
 * - PS1: a whole memory card image (128 KB .mcr/.mcd/.srm, or .gme with its DexDrive header) as the game's card.
 *
 * Nothing is overwritten outright: what was there goes to saves/import-backup/<time>/ first.
 */
object SaveImporter {
    const val MAX_BYTES = 64 * 1024 * 1024

    enum class Problem {
        UNKNOWN_FORMAT, SYSTEM_UNSUPPORTED, PS2_CBS, PS2_OTHER_CORE, PS1_SINGLE_SAVE, OTHER_GAME, NO_SAVE_IN_ZIP, TOO_LARGE,
    }

    sealed class Plan {
        /** One file replaces the game's save file. */
        data class Battery(val target: File, val bytes: ByteArray) : Plan()
        data class Ps1Card(val target: File, val bytes: ByteArray) : Plan()

        /**
         * A GameCube save for [regionDir]. [saveCode] is the game code inside the save, [gameCode] the disc's
         * (null when the disc could not be read); when only the region letter differs, [apply] can relabel it.
         */
        data class GameCube(
            val cardDir: File,
            val regionDir: String,
            val gci: ByteArray,
            val saveCode: String,
            val gameCode: String?,
            val saveName: String,
        ) : Plan() {
            val regionOnlyMismatch: Boolean get() = gameCode != null && saveCode != gameCode && saveCode.take(3) == gameCode.take(3)
        }

        /** A folder of files: a PS2 save on Play!'s card, or a PSP save in SAVEDATA. */
        data class Folder(val parent: File, val folderName: String, val files: Map<String, ByteArray>, val ps2: Boolean) : Plan()

        data class Unsupported(val problem: Problem, val detail: String = "") : Plan()
    }

    /** Everything [analyze] needs to know about the game besides the file. */
    data class Target(
        val system: SystemId?,
        val romPath: String,
        val coreId: String?,
        /** The system's saves folder (OneEmu/saves/<system>). */
        val savesDir: File,
    )

    // ---------------------------------------------------------------- analysis

    fun analyze(target: Target, fileName: String, bytes: ByteArray): Plan {
        if (bytes.size > MAX_BYTES) return Plan.Unsupported(Problem.TOO_LARGE)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val romBase = File(target.romPath).nameWithoutExtension
        return when (target.system) {
            SystemId.GC -> gameCube(target, bytes)
            SystemId.PS2 -> ps2(target, ext, bytes)
            SystemId.PSP -> psp(target, bytes)
            SystemId.PSX -> ps1(target, romBase, ext, bytes)
            SystemId.NES, SystemId.GB, SystemId.GBC, SystemId.GBA, SystemId.NDS, SystemId.MD, SystemId.SMS, SystemId.GG ->
                Plan.Battery(File(target.savesDir, "$romBase.srm"), stripDsvFooter(bytes))
            else -> Plan.Unsupported(Problem.SYSTEM_UNSUPPORTED)
        }
    }

    /** DeSmuME's .dsv carries a 122-byte footer after the save proper. */
    private fun stripDsvFooter(bytes: ByteArray): ByteArray {
        val marker = "|<--Snip above here to create a raw sav by excluding this DeSmuME savedata footer:".toByteArray()
        val at = indexOf(bytes, marker)
        return if (at > 0) bytes.copyOf(at) else bytes
    }

    // ---- GameCube

    private const val GC_BLOCK = 0x2000
    private const val DENTRY = 0x40

    private fun gameCube(target: Target, bytes: ByteArray): Plan {
        val gci = toGci(bytes) ?: return Plan.Unsupported(Problem.UNKNOWN_FORMAT)
        val saveCode = String(gci, 0, 4, Charsets.ISO_8859_1)
        val gameId = runCatching { RomInfo.discId(File(target.romPath)) }.getOrNull()
        val gameCode = gameId?.take(4)
        if (gameCode != null && saveCode.take(3) != gameCode.take(3)) {
            return Plan.Unsupported(Problem.OTHER_GAME, "$saveCode ≠ $gameCode")
        }
        val regionDir = regionDir(gameCode ?: saveCode)
        val cardDir = File(target.savesDir, "User/GC/$regionDir/Card A")
        val name = String(gci, 8, 32, Charsets.ISO_8859_1).trimEnd('\u0000').ifBlank { "save" }
        return Plan.GameCube(cardDir, regionDir, gci, saveCode, gameCode, name)
    }

    /** .gci as is; .gcs and Datel .sav turned into one, the way Dolphin reads them (GCMemcardUtils.cpp). */
    internal fun toGci(bytes: ByteArray): ByteArray? {
        fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
        return when (bytes.size % GC_BLOCK) {
            DENTRY -> bytes.takeIf { be16(it, 0x38) * GC_BLOCK + DENTRY == it.size }
            0x150 -> {
                if (!startsWith(bytes, "GCSAVE")) return null
                val out = ByteArray(DENTRY + bytes.size - 0x150)
                System.arraycopy(bytes, 0x110, out, 0, DENTRY)
                // GCS files leave the block count at 1; the real count follows from the size.
                val blocks = (bytes.size - 0x150) / GC_BLOCK
                out[0x38] = (blocks shr 8).toByte(); out[0x39] = blocks.toByte()
                System.arraycopy(bytes, 0x150, out, DENTRY, bytes.size - 0x150)
                out
            }
            0xC0 -> {
                if (!startsWith(bytes, "DATELGC_SAVE")) return null
                val entry = bytes.copyOfRange(0x80, 0x80 + DENTRY)
                for (p in intArrayOf(0x06, 0x2C, 0x2E, 0x30, 0x32, 0x34, 0x36, 0x38, 0x3A, 0x3C, 0x3E)) {
                    val t = entry[p]; entry[p] = entry[p + 1]; entry[p + 1] = t
                }
                if (be16(entry, 0x38) * GC_BLOCK + 0xC0 != bytes.size) return null
                entry + bytes.copyOfRange(0xC0, bytes.size)
            }
            else -> null
        }
    }

    /** Dolphin's GCI folder for a game code's region letter. */
    internal fun regionDir(code: String): String = when (code.getOrNull(3)?.uppercaseChar()) {
        'E', 'N' -> "USA"
        'J', 'K', 'W' -> "JAP"
        else -> "EUR"
    }

    // ---- PS2

    private fun ps2(target: Target, ext: String, bytes: ByteArray): Plan {
        if (target.coreId != null && target.coreId != "play") return Plan.Unsupported(Problem.PS2_OTHER_CORE, target.coreId)
        if (ext == "cbs") return Plan.Unsupported(Problem.PS2_CBS)
        val parsed = runCatching {
            when {
                le16(bytes, 0) == 0x8427 -> parsePsu(bytes)
                startsWith(bytes, "Ps2PowerSave") -> parseMax(bytes)
                le32(bytes, 0) == 0x0DL && String(bytes, 4, 13, Charsets.ISO_8859_1) == "SharkPortSave" -> parseXps(bytes)
                else -> null
            }
        }.getOrNull() ?: return Plan.Unsupported(Problem.UNKNOWN_FORMAT)
        return Plan.Folder(ps2CardDir(target.savesDir), parsed.first, parsed.second, ps2 = true)
    }

    /**
     * Play!'s first memory card: a folder, "vfs/mc0" under its data folder (Play Data Files in the core's save
     * directory). An existing one is looked for first, in case a build keeps it elsewhere.
     */
    private fun ps2CardDir(savesDir: File): File =
        savesDir.walkTopDown().maxDepth(4).firstOrNull { it.isDirectory && it.name == "mc0" && it.parentFile?.name == "vfs" }
            ?: File(savesDir, "Play Data Files/vfs/mc0")

    /** .psu: 512-byte entry headers; the first is the save's folder, then ".", "..", then each file padded to 1 KB. */
    internal fun parsePsu(b: ByteArray): Pair<String, Map<String, ByteArray>>? {
        var pos = 0
        var folder: String? = null
        val files = LinkedHashMap<String, ByteArray>()
        while (pos + 0x200 <= b.size) {
            val type = le16(b, pos)
            val size = le32(b, pos + 4).toInt()
            val name = cString(b, pos + 0x40, 0x1C0)
            pos += 0x200
            when (type) {
                0x8427 -> if (size != 0 && folder == null && name != "." && name != "..") folder = name
                0x8497 -> {
                    if (size < 0 || pos + size > b.size) return null
                    files[name] = b.copyOfRange(pos, pos + size)
                    pos += size
                    if (size and 0x3FF != 0) pos += 0x400 - (size and 0x3FF)
                }
                else -> return null
            }
        }
        return folder?.takeIf { files.isNotEmpty() }?.let { it to files }
    }

    /** .max: header, then an LZARI stream of (size, name[32], data) records aligned as Play! does. */
    internal fun parseMax(b: ByteArray): Pair<String, Map<String, ByteArray>>? {
        // magic[12], checksum, folder[32], icon.sys name[32], compressed size, file count, then the stream.
        val folder = cString(b, 0x10, 32)
        val count = le32(b, 0x54).toInt()
        val data = LzAri.decompress(b, 0x58)
        val files = LinkedHashMap<String, ByteArray>()
        var pos = 0
        repeat(count) {
            if (pos + 36 > data.size) return null
            val size = le32(data, pos).toInt()
            val name = cString(data, pos + 4, 32)
            pos += 36
            if (size < 0 || pos + size > data.size) return null
            files[name] = data.copyOfRange(pos, pos + size)
            pos += size
            pos += (((pos + 8) + 15) and 15.inv()) - 8 - pos
        }
        return folder.takeIf { it.isNotBlank() && files.isNotEmpty() }?.let { it to files }
    }

    /** .xps (Datel X-Port / SharkPort): three length-prefixed strings, then a tree of descriptors. */
    internal fun parseXps(b: ByteArray): Pair<String, Map<String, ByteArray>>? {
        var pos = 0x15
        repeat(3) { pos += 4 + le32(b, pos).toInt() }
        val end = le32(b, pos) + pos + 4
        pos += 4
        var folder: String? = null
        val files = LinkedHashMap<String, ByteArray>()
        while (pos + 2 <= b.size && pos < end) {
            val descLen = le16(b, pos)
            val name = cString(b, pos + 2, 0x40)
            val length = le32(b, pos + 0x42).toInt()
            val attr = le32(b, pos + 0x4E)
            pos += descLen
            if (attr and 0x2000L != 0L) {
                if (folder == null) folder = name
            } else {
                if (length < 0 || pos + length > b.size) return null
                files[name] = b.copyOfRange(pos, pos + length)
                pos += length
            }
        }
        return folder?.takeIf { files.isNotEmpty() }?.let { it to files }
    }

    // ---- PSP

    private fun psp(target: Target, bytes: ByteArray): Plan {
        if (!(bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) return Plan.Unsupported(Problem.UNKNOWN_FORMAT)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name.replace('\\', '/')] = zip.readBytes()
            }
        }
        // The save is the folder that holds PARAM.SFO, wherever it sits in the zip.
        val sfo = entries.keys.firstOrNull { it.substringAfterLast('/').equals("PARAM.SFO", ignoreCase = true) }
            ?: return Plan.Unsupported(Problem.NO_SAVE_IN_ZIP)
        val prefix = sfo.substringBeforeLast('/', "")
        val folder = prefix.substringAfterLast('/').ifBlank { return Plan.Unsupported(Problem.NO_SAVE_IN_ZIP) }
        val files = entries.filterKeys { it.startsWith("$prefix/") && '/' !in it.removePrefix("$prefix/") }
            .mapKeys { it.key.removePrefix("$prefix/") }
        return Plan.Folder(File(target.savesDir, "PSP/SAVEDATA"), folder, files, ps2 = false)
    }

    // ---- PS1

    private const val PS1_CARD = 128 * 1024

    private fun ps1(target: Target, romBase: String, ext: String, bytes: ByteArray): Plan {
        val card = when {
            bytes.size == PS1_CARD && startsWith(bytes, "MC") -> bytes
            // DexDrive .gme: a 3904-byte header before the card.
            bytes.size == PS1_CARD + 3904 && startsWith(bytes, "123-456-STD") -> bytes.copyOfRange(3904, bytes.size)
            ext in setOf("mcs", "psv", "ps1", "mcb", "mcx", "pda") -> return Plan.Unsupported(Problem.PS1_SINGLE_SAVE)
            else -> return Plan.Unsupported(Problem.UNKNOWN_FORMAT)
        }
        return Plan.Ps1Card(File(target.savesDir, "$romBase.srm"), card)
    }

    // ---------------------------------------------------------------- applying

    data class Applied(val written: File, val backup: File?)

    /**
     * Writes [plan]; [relabelRegion] rewrites a GameCube save's region letter to the disc's first. Whatever it
     * replaces is moved to [backupRoot]/<time>/ beforehand.
     */
    fun apply(plan: Plan, backupRoot: File, relabelRegion: Boolean = false): Applied {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupDir = File(backupRoot, stamp)
        fun backUp(f: File): File? {
            if (!f.exists()) return null
            backupDir.mkdirs()
            val dest = File(backupDir, f.name)
            if (!f.renameTo(dest)) {
                f.copyRecursively(dest, overwrite = true)
                f.deleteRecursively()
            }
            return dest
        }
        return when (plan) {
            is Plan.Battery -> write(plan.target, plan.bytes, ::backUp)
            is Plan.Ps1Card -> write(plan.target, plan.bytes, ::backUp)
            is Plan.GameCube -> {
                val gci = plan.gci.copyOf()
                val code = if (relabelRegion && plan.regionOnlyMismatch && plan.gameCode != null) {
                    gci[3] = plan.gameCode[3].code.toByte()
                    plan.gameCode
                } else plan.saveCode
                val maker = String(gci, 4, 2, Charsets.ISO_8859_1)
                val safe = plan.saveName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                // Dolphin's own naming, so a save exported from it and this one land on the same file.
                val target = File(plan.cardDir, "$maker-$code-$safe.gci")
                // A save of the same game and name already on the card is the one being replaced.
                plan.cardDir.listFiles()?.filter { f ->
                    f.extension.equals("gci", true) && f != target && f.length() >= DENTRY &&
                        runCatching { f.inputStream().use { s -> val h = ByteArray(DENTRY); s.read(h); String(h, 0, 4, Charsets.ISO_8859_1) == code && String(h, 8, 32, Charsets.ISO_8859_1) == String(gci, 8, 32, Charsets.ISO_8859_1) } }.getOrDefault(false)
                }?.forEach { backUp(it) }
                write(target, gci, ::backUp)
            }
            is Plan.Folder -> {
                val dir = File(plan.parent, plan.folderName)
                val backup = backUp(dir)
                dir.mkdirs()
                for ((name, data) in plan.files) File(dir, name).writeBytes(data)
                Applied(dir, backup)
            }
            is Plan.Unsupported -> error("nothing to apply")
        }
    }

    private fun write(target: File, bytes: ByteArray, backUp: (File) -> File?): Applied {
        target.parentFile?.mkdirs()
        val backup = backUp(target)
        target.writeBytes(bytes)
        return Applied(target, backup)
    }

    // ---------------------------------------------------------------- bytes

    private fun le16(b: ByteArray, o: Int) = if (o + 2 > b.size) -1 else (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Long = if (o + 4 > b.size) -1 else
        ((b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24))
    private fun cString(b: ByteArray, o: Int, max: Int): String {
        if (o >= b.size) return ""
        var end = o
        while (end < minOf(b.size, o + max) && b[end] != 0.toByte()) end++
        return String(b, o, end - o, Charsets.ISO_8859_1)
    }
    private fun startsWith(b: ByteArray, s: String): Boolean = b.size >= s.length && s.indices.all { b[it] == s[it].code.toByte() }
    private fun indexOf(b: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Haruhiko Okumura's LZARI decoder (1989, "use, distribute, and modify this program freely"), as Action
 * Replay Max saves use it; a straight port of the one in Play!'s Framework (LzAri.cpp).
 */
internal object LzAri {
    private const val N = 4096
    private const val F = 60
    private const val THRESHOLD = 2
    private const val M = 15
    private const val Q1 = 1L shl M
    private const val Q2 = 2 * Q1
    private const val Q3 = 3 * Q1
    private const val Q4 = 4 * Q1
    private const val MAX_CUM = Q1 - 1
    private const val N_CHAR = 256 - THRESHOLD + F

    fun decompress(src: ByteArray, offset: Int): ByteArray {
        var inPos = offset
        fun read8(): Int = if (inPos < src.size) src[inPos++].toInt() and 0xff else { inPos++; 0 }
        val textSize = (read8() or (read8() shl 8) or (read8() shl 16) or (read8() shl 24)).toLong() and 0xffffffffL
        if (textSize == 0L) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(textSize.toInt().coerceAtMost(64 * 1024 * 1024))

        var getBuffer = 0
        var getMask = 0
        fun getBit(): Int {
            getMask = getMask shr 1
            if (getMask == 0) { getBuffer = read8(); getMask = 128 }
            return if (getBuffer and getMask != 0) 1 else 0
        }

        val charToSym = IntArray(N_CHAR)
        val symToChar = IntArray(N_CHAR + 1)
        val symFreq = LongArray(N_CHAR + 1)
        val symCum = LongArray(N_CHAR + 1)
        val positionCum = LongArray(N + 1)
        // StartModel
        symCum[N_CHAR] = 0
        for (sym in N_CHAR downTo 1) {
            val ch = sym - 1
            charToSym[ch] = sym; symToChar[sym] = ch
            symFreq[sym] = 1
            symCum[sym - 1] = symCum[sym] + symFreq[sym]
        }
        symFreq[0] = 0
        positionCum[N] = 0
        for (i in N downTo 1) positionCum[i - 1] = positionCum[i] + 10000 / (i + 200)

        fun updateModel(sym: Int) {
            if (symCum[0] >= MAX_CUM) {
                var c = 0L
                for (i in N_CHAR downTo 1) {
                    symCum[i] = c
                    symFreq[i] = (symFreq[i] + 1) shr 1
                    c += symFreq[i]
                }
                symCum[0] = c
            }
            var i = sym
            while (symFreq[i] == symFreq[i - 1]) i--
            if (i < sym) {
                val chI = symToChar[i]; val chSym = symToChar[sym]
                symToChar[i] = chSym; symToChar[sym] = chI
                charToSym[chI] = sym; charToSym[chSym] = i
            }
            symFreq[i]++
            while (--i >= 0) symCum[i]++
        }

        var low = 0L
        var high = Q4
        var value = 0L
        for (i in 0 until M + 2) value = 2 * value + getBit()

        fun normalise() {
            while (true) {
                if (low >= Q2) { value -= Q2; low -= Q2; high -= Q2 }
                else if (low >= Q1 && high <= Q3) { value -= Q1; low -= Q1; high -= Q1 }
                else if (high > Q2) break
                low += low; high += high
                value = 2 * value + getBit()
            }
        }

        fun decodeChar(): Int {
            val range = high - low
            val x = ((value - low + 1) * symCum[0] - 1) / range
            var i = 1; var j = N_CHAR
            while (i < j) { val k = (i + j) / 2; if (symCum[k] > x) i = k + 1 else j = k }
            val sym = i
            high = low + (range * symCum[sym - 1]) / symCum[0]
            low += (range * symCum[sym]) / symCum[0]
            normalise()
            val ch = symToChar[sym]
            updateModel(sym)
            return ch
        }

        fun decodePosition(): Int {
            val range = high - low
            val x = ((value - low + 1) * positionCum[0] - 1) / range
            var i = 1; var j = N
            while (i < j) { val k = (i + j) / 2; if (positionCum[k] > x) i = k + 1 else j = k }
            val position = i - 1
            high = low + (range * positionCum[position]) / positionCum[0]
            low += (range * positionCum[position + 1]) / positionCum[0]
            normalise()
            return position
        }

        val text = ByteArray(N + F - 1) { ' '.code.toByte() }
        var r = N - F
        var count = 0L
        while (count < textSize) {
            val c = decodeChar()
            if (c < 256) {
                out.write(c); text[r++] = c.toByte(); r = r and (N - 1); count++
            } else {
                val i = (r - decodePosition() - 1) and (N - 1)
                val j = c - 255 + THRESHOLD
                for (k in 0 until j) {
                    val b = text[(i + k) and (N - 1)]
                    out.write(b.toInt() and 0xff); text[r++] = b; r = r and (N - 1); count++
                }
            }
        }
        return out.toByteArray()
    }
}
