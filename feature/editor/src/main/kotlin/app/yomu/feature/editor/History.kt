package app.yomu.feature.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * Disk based undo/redo history for a working copy directory.
 *
 * Every change declares the files (relative to [root]) it is going to touch. Their previous state is copied
 * into `history/<id>/before` before the change runs. Undo first stores the current state into `after`
 * (so the step can be redone) and then restores `before`. Files that did not exist are recorded as absent
 * and deleted on restore. The history survives process death, so a draft can be resumed with its undo stack.
 */
class History(private val root: File, private val dir: File, private val limit: Int = MAX_STEPS) {
    data class Step(val id: Long, val label: String, val files: List<String>)

    private val steps = mutableListOf<Step>()
    private var position = 0 // number of applied steps
    private var nextId = 1L

    val canUndo: Boolean get() = position > 0
    val canRedo: Boolean get() = position < steps.size
    val undoLabel: String? get() = steps.getOrNull(position - 1)?.label
    val redoLabel: String? get() = steps.getOrNull(position)?.label
    val size: Int get() = steps.size

    init {
        load()
    }

    /** Runs [block] as one undoable step. If it throws, the touched files are restored and nothing is recorded. */
    fun <T> change(label: String, files: Collection<String>, block: () -> T): T {
        val declared = files.map { it.trimStart('/') }.distinct()
        val stepDir = File(dir, nextId.toString())
        stepDir.deleteRecursively()
        snapshot(stepDir, "before", declared)
        val existing = listFiles()
        val result = try {
            block()
        } catch (e: Throwable) {
            restore(stepDir, "before", declared)
            (listFiles() - existing).forEach { File(root, it).delete() }
            stepDir.deleteRecursively()
            throw e
        }
        // Files created by the change are recorded as "absent before" so that undo removes them.
        val created = (listFiles() - existing - declared.toSet()).sorted()
        if (created.isNotEmpty()) {
            val absent = File(stepDir, "before.absent")
            absent.writeText((absent.readLines().filter { it.isNotEmpty() } + created).joinToString("\n"))
        }
        val step = Step(nextId++, label, declared + created)
        // A new change drops the redo tail.
        steps.subList(position, steps.size).toList().forEach { File(dir, it.id.toString()).deleteRecursively() }
        while (steps.size > position) steps.removeAt(steps.lastIndex)
        steps += step
        position = steps.size
        trim()
        save()
        return result
    }

    fun undo(): Step? {
        if (!canUndo) return null
        val step = steps[position - 1]
        val stepDir = File(dir, step.id.toString())
        snapshot(stepDir, "after", step.files)
        restore(stepDir, "before", step.files)
        position--
        save()
        return step
    }

    fun redo(): Step? {
        if (!canRedo) return null
        val step = steps[position]
        val stepDir = File(dir, step.id.toString())
        if (!File(stepDir, "after.absent").exists()) return null
        restore(stepDir, "after", step.files)
        position++
        save()
        return step
    }

    fun clear() {
        dir.deleteRecursively()
        steps.clear()
        position = 0
        save()
    }

    private fun listFiles(): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toSet()

    fun sizeOnDisk(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun trim() {
        while (steps.size > limit || (steps.size > MIN_STEPS && sizeOnDisk() > MAX_BYTES)) {
            val first = steps.removeAt(0)
            File(dir, first.id.toString()).deleteRecursively()
            position = (position - 1).coerceAtLeast(0)
        }
    }

    private fun snapshot(stepDir: File, kind: String, files: List<String>) {
        val target = File(stepDir, kind)
        target.deleteRecursively()
        val absent = mutableListOf<String>()
        files.forEach { path ->
            val src = File(root, path)
            if (src.isFile) src.copyTo(File(target, path), overwrite = true) else absent += path
        }
        stepDir.mkdirs()
        File(stepDir, "$kind.absent").writeText(absent.joinToString("\n"))
    }

    private fun restore(stepDir: File, kind: String, files: List<String>) {
        val absent = File(stepDir, "$kind.absent").takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val source = File(stepDir, kind)
        files.forEach { path ->
            val dst = File(root, path)
            if (path in absent) {
                dst.delete()
            } else {
                val src = File(source, path)
                if (src.isFile) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }
            }
        }
    }

    private fun save() {
        dir.mkdirs()
        val json = buildJsonObject {
            put("position", position)
            put("nextId", nextId)
            put(
                "steps",
                JsonArray(
                    steps.map { s ->
                        buildJsonObject {
                            put("id", s.id)
                            put("label", s.label)
                            put("files", JsonArray(s.files.map { JsonPrimitive(it) }))
                        }
                    },
                ),
            )
        }
        File(dir, INDEX).writeText(json.toString())
    }

    private fun load() {
        val file = File(dir, INDEX)
        if (!file.exists()) return
        runCatching {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            steps.clear()
            obj["steps"]?.jsonArray?.forEach { e ->
                val o = e.jsonObject
                steps += Step(
                    o["id"]!!.jsonPrimitive.long,
                    o["label"]!!.jsonPrimitive.content,
                    o["files"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
            }
            position = obj["position"]!!.jsonPrimitive.int.coerceIn(0, steps.size)
            nextId = obj["nextId"]!!.jsonPrimitive.long
        }.onFailure {
            steps.clear()
            position = 0
        }
    }

    companion object {
        const val MAX_STEPS = 100
        const val MIN_STEPS = 50
        const val MAX_BYTES = 400L * 1024 * 1024
        private const val INDEX = "index.json"
    }
}
