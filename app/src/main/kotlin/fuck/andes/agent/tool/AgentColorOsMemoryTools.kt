package fuck.andes.agent.tool

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import fuck.andes.agent.device.BoundedRootCommandExecutor
import fuck.andes.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只读检索 ColorOS 系统记忆数据库。数据库路径、表、字段和查询均由 Eta 固定，
 * 模型只能提供有界关键词与返回数量，不能接触任意路径或 SQL。
 */
internal class AgentColorOsMemoryTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) {
    fun search(args: JSONObject): AgentModelClient.ToolResult = querySnapshot { database ->
        searchDatabase(database, args)
    }

    fun searchOrders(args: JSONObject): AgentModelClient.ToolResult = querySnapshot { database ->
        searchDatabase(database, args, ordersOnly = true, toolName = "search_personal_orders")
    }

    fun searchSavedPlaces(args: JSONObject): AgentModelClient.ToolResult = querySnapshot { database ->
        searchPlaces(database, args)
    }

    private fun querySnapshot(block: (SQLiteDatabase) -> String): AgentModelClient.ToolResult = synchronized(snapshotLock) {
        val snapshot = createSnapshot()
            ?: return@synchronized sensitive(error("COLOROS_MEMORY_UNAVAILABLE", "ColorOS 系统记忆暂时不可访问"))
        try {
            val database = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            }.getOrElse { throwable ->
                return@synchronized sensitive(
                    error(
                        if (throwable is SQLiteException) {
                            "COLOROS_MEMORY_SNAPSHOT_INVALID"
                        } else {
                            "COLOROS_MEMORY_OPEN_FAILED"
                        },
                        "ColorOS 系统记忆快照无法读取",
                    ),
                )
            }
            database.use { db ->
                runCatching { sensitive(block(db)) }
                    .getOrElse {
                        sensitive(
                            error(
                                "COLOROS_MEMORY_QUERY_FAILED",
                                "ColorOS 系统记忆查询失败",
                            ),
                        )
                    }
            }
        } finally {
            deleteSnapshot(snapshot)
        }
    }

    private fun createSnapshot(): File? {
        cleanupStaleSnapshots()
        val snapshot = runCatching {
            File.createTempFile(SNAPSHOT_PREFIX, DATABASE_SUFFIX, context.cacheDir)
        }.getOrNull() ?: return null
        val wal = File(snapshot.absolutePath + WAL_SUFFIX)
        if (!runCatching { wal.createNewFile() }.getOrDefault(false)) {
            snapshot.delete()
            return null
        }
        val userId = context.dataDir.parentFile?.name?.toIntOrNull() ?: return null
        val source = "/data/user/$userId/$MEMORY_PACKAGE/databases/$DATABASE_NAME"
        val result = root.execute(
            buildSnapshotCommand(source, snapshot, wal),
            timeoutMillis = SNAPSHOT_TIMEOUT_MS,
            maxOutputBytes = 8 * 1024,
        )
        if (result.ok) return snapshot
        deleteSnapshot(snapshot)
        return null
    }

    private fun cleanupStaleSnapshots() {
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(SNAPSHOT_PREFIX) }
            ?.forEach(File::delete)
    }

    private fun buildSnapshotCommand(source: String, snapshot: File, wal: File): String {
        val sourceWal = source + WAL_SUFFIX
        return "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ ! -L ${shellQuote(source)} ] || exit 22; " +
            "[ \"\$(stat -c %s ${shellQuote(source)})\" -le $MAX_DATABASE_BYTES ] || exit 23; " +
            "cp ${shellQuote(source)} ${shellQuote(snapshot.absolutePath)} || exit 24; " +
            "if [ -f ${shellQuote(sourceWal)} ]; then " +
            "[ ! -L ${shellQuote(sourceWal)} ] || exit 25; " +
            "[ \"\$(stat -c %s ${shellQuote(sourceWal)})\" -le $MAX_WAL_BYTES ] || exit 26; " +
            "cp ${shellQuote(sourceWal)} ${shellQuote(wal.absolutePath)} || exit 27; " +
            "else rm -f ${shellQuote(wal.absolutePath)}; fi"
    }

    private fun searchDatabase(
        database: SQLiteDatabase,
        args: JSONObject,
        ordersOnly: Boolean = false,
        toolName: String = TOOL_NAME,
    ): String {
        val memoryColumns = database.tableColumns(MEMORIES_TABLE)
        if (memoryColumns.isEmpty() || "memory_id" !in memoryColumns) {
            return error("COLOROS_MEMORY_SCHEMA_UNSUPPORTED", "当前系统记忆数据库结构暂不受支持")
        }
        val projection = CORE_COLUMNS.filter(memoryColumns::contains)
        val keyword = args.optString("query").trim()
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if ("deleted" in memoryColumns) selectionParts += "deleted=0"
        if ("recycle_time" in memoryColumns) selectionParts += "recycle_time=0"
        if (ordersOnly) {
            val orderPredicates = mutableListOf<String>()
            if ("package_name" in memoryColumns) {
                orderPredicates += "package_name IN (${ORDER_PACKAGES.joinToString { "?" }})"
                selectionArgs += ORDER_PACKAGES
            }
            val orderColumns = ORDER_SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (orderColumns.isNotEmpty()) {
                ORDER_KEYWORDS.forEach { keywordValue ->
                    val pattern = "%${keywordValue.escapeLikePattern()}%"
                    orderPredicates += orderColumns.joinToString(" OR ", prefix = "(", postfix = ")") { column ->
                        "$column LIKE ? ESCAPE '\\' COLLATE NOCASE"
                    }
                    repeat(orderColumns.size) { selectionArgs += pattern }
                }
            }
            listOf("bills", "pickup_codes", "shipments").forEach { table ->
                val columns = database.tableColumns(table)
                if ("associate_memory_id" in columns) {
                    orderPredicates += "EXISTS (SELECT 1 FROM $table WHERE associate_memory_id=$MEMORIES_TABLE.memory_id)"
                }
            }
            if (orderPredicates.isEmpty()) {
                return error("COLOROS_ORDER_SCHEMA_UNSUPPORTED", "当前系统记忆没有可用的订单字段")
            }
            selectionParts += orderPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
        }
        if (keyword.isNotBlank()) {
            val pattern = "%${keyword.escapeLikePattern()}%"
            val searchPredicates = mutableListOf<String>()
            val searchColumns = SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (searchColumns.isNotEmpty()) {
                searchPredicates += searchColumns.map { column ->
                    "$column LIKE ? ESCAPE '\\' COLLATE NOCASE"
                }
                repeat(searchColumns.size) { selectionArgs += pattern }
            }
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val searchable = spec.searchColumns.filter(columns::contains)
                if (searchable.isEmpty()) return@forEach
                searchPredicates += searchable.joinToString(
                    separator = " OR ",
                    prefix = "EXISTS (SELECT 1 FROM ${spec.table} WHERE " +
                        "${spec.linkColumn}=$MEMORIES_TABLE.memory_id AND (",
                    postfix = "))",
                ) { column -> "$column LIKE ? ESCAPE '\\' COLLATE NOCASE" }
                repeat(searchable.size) { selectionArgs += pattern }
            }
            if (searchPredicates.isNotEmpty()) {
                selectionParts += searchPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
            }
        }
        val cursor = database.query(
            MEMORIES_TABLE,
            projection.toTypedArray(),
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            if ("created_time" in memoryColumns) "created_time DESC" else null,
            limit.toString(),
        )
        val items = JSONArray()
        var resultChars = 0
        var resultTruncated = false
        cursor.use {
            while (it.moveToNext()) {
                val item = it.currentRow()
                val memoryId = item.optString("memory_id")
                if (memoryId.isNotBlank()) {
                    val details = relatedDetails(database, memoryId)
                    if (details.length() > 0) item.put("details", details)
                }
                var serializedLength = item.toString().length
                if (resultChars + serializedLength > MAX_RESULT_CHARS && item.has("details")) {
                    item.remove("details")
                    item.put("details_truncated", true)
                    serializedLength = item.toString().length
                }
                if (resultChars + serializedLength > MAX_RESULT_CHARS) {
                    resultTruncated = true
                    break
                }
                items.put(item)
                resultChars += serializedLength
            }
            if (!it.isAfterLast) resultTruncated = true
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", toolName)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", resultTruncated || items.length() == limit)
            .toString()
    }

    private fun searchPlaces(database: SQLiteDatabase, args: JSONObject): String {
        val columns = database.tableColumns("memory_address")
        if ("memory_id" !in columns) {
            return error("COLOROS_PLACE_SCHEMA_UNSUPPORTED", "当前系统记忆没有可用的地点字段")
        }
        val projection = PLACE_COLUMNS.filter(columns::contains)
        val keyword = args.optString("query").trim()
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val searchable = PLACE_SEARCH_COLUMNS.filter(columns::contains)
        val selection = if (keyword.isNotBlank() && searchable.isNotEmpty()) {
            searchable.joinToString(" OR ", prefix = "(", postfix = ")") { "$it LIKE ? ESCAPE '\\' COLLATE NOCASE" }
        } else {
            null
        }
        val selectionArgs = if (selection != null) {
            Array(searchable.size) { "%${keyword.escapeLikePattern()}%" }
        } else {
            null
        }
        val items = JSONArray()
        database.query(
            "memory_address",
            projection.toTypedArray(),
            selection,
            selectionArgs,
            null,
            null,
            if ("create_time" in columns) "create_time DESC" else null,
            limit.toString(),
        ).use { cursor -> while (cursor.moveToNext()) items.put(cursor.currentRow()) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_saved_places")
            .put("items", items)
            .put("count", items.length())
            .put("truncated", items.length() == limit)
            .toString()
    }

    private fun relatedDetails(database: SQLiteDatabase, memoryId: String): JSONObject =
        JSONObject().also { details ->
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val projection = spec.columns.filter(columns::contains)
                if (projection.isEmpty()) return@forEach
                val selection = buildString {
                    append(spec.linkColumn).append("=?")
                    spec.activeColumn?.takeIf(columns::contains)?.let { append(" AND ").append(it).append("=0") }
                }
                val rows = database.queryRows(
                    table = spec.table,
                    projection = projection,
                    selection = selection,
                    selectionArgs = arrayOf(memoryId),
                    limit = RELATED_LIMIT,
                )
                if (rows.length() > 0) details.put(spec.outputName, rows)
            }
        }

    private fun SQLiteDatabase.queryRows(
        table: String,
        projection: List<String>,
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): JSONArray = JSONArray().also { rows ->
        query(
            table,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            null,
            null,
            null,
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows.put(cursor.currentRow())
        }
    }

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> =
        runCatching {
            rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0) add(cursor.getString(nameIndex))
                    }
                }
            }
        }.getOrDefault(emptySet())

    private fun Cursor.currentRow(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index).bounded()
                Cursor.FIELD_TYPE_BLOB -> continue
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    private fun String.bounded(): String =
        if (length <= MAX_FIELD_CHARS) this else take(MAX_FIELD_CHARS) + "…"

    private fun String.escapeLikePattern(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun deleteSnapshot(snapshot: File) {
        listOf(
            snapshot,
            File(snapshot.absolutePath + WAL_SUFFIX),
            File(snapshot.absolutePath + SHM_SUFFIX),
            File(snapshot.absolutePath + JOURNAL_SUFFIX),
        ).forEach(File::delete)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private data class DetailSpec(
        val outputName: String,
        val table: String,
        val linkColumn: String = "associate_memory_id",
        val activeColumn: String? = null,
        val columns: List<String>,
        val searchColumns: List<String>,
    )

    private companion object {
        const val TOOL_NAME = "search_coloros_memories"
        const val MEMORY_PACKAGE = "com.oplus.aimemory"
        const val DATABASE_NAME = "ai_memory"
        const val MEMORIES_TABLE = "memories"
        const val SNAPSHOT_PREFIX = "eta-coloros-memory-"
        const val DATABASE_SUFFIX = ".db"
        const val WAL_SUFFIX = "-wal"
        const val SHM_SUFFIX = "-shm"
        const val JOURNAL_SUFFIX = "-journal"
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val RELATED_LIMIT = 3
        const val MAX_FIELD_CHARS = 4_000
        const val MAX_RESULT_CHARS = 240_000
        const val MAX_DATABASE_BYTES = 64L * 1024 * 1024
        const val MAX_WAL_BYTES = 64L * 1024 * 1024
        const val SNAPSHOT_TIMEOUT_MS = 15_000L

        val snapshotLock = Any()
        val CORE_COLUMNS = listOf(
            "memory_id",
            "data_source",
            "data_text",
            "package_name",
            "app_name",
            "activity_name",
            "screenshot",
            "audio_file",
            "deeplink",
            "data_category",
            "data_entity",
            "ocr_entity",
            "trigger_type",
            "memory_type",
            "scene_name",
            "scene_type",
            "data_abstract",
            "extra_data",
            "sub_scene_data",
            "created_time",
            "update_time",
            "notes",
            "image_count",
            "classify",
            "data_text_cleanup",
        )
        val SEARCH_COLUMNS = listOf(
            "data_text",
            "data_text_cleanup",
            "data_abstract",
            "notes",
            "app_name",
            "package_name",
            "data_category",
            "data_entity",
            "ocr_entity",
            "scene_name",
            "classify",
        )
        val ORDER_SEARCH_COLUMNS = listOf(
            "data_text", "data_text_cleanup", "data_abstract", "notes", "app_name",
            "scene_name", "classify",
        )
        val ORDER_KEYWORDS = listOf(
            "订单", "外卖", "取餐", "配送", "骑手", "快递", "车票", "机票", "酒店", "电影票",
        )
        val ORDER_PACKAGES = listOf(
            "com.sankuai.meituan", "me.ele", "com.ss.android.ugc.lifeservices",
            "com.jingdong.app.mall", "com.taobao.taobao", "com.xunmeng.pinduoduo",
            "com.taobao.trip", "com.sdu.didi.psnger",
        )
        val PLACE_COLUMNS = listOf(
            "memory_id", "category", "sub_type", "name", "address", "full_address",
            "country", "province", "city", "district", "location", "longitude", "latitude",
            "reason", "insight", "deepLink", "shopHours",
        )
        val PLACE_SEARCH_COLUMNS = listOf(
            "category", "sub_type", "name", "address", "full_address", "country", "province",
            "city", "district", "location", "reason", "insight",
        )
        val DETAIL_SPECS = listOf(
            DetailSpec(
                outputName = "bills",
                table = "bills",
                activeColumn = "status",
                columns = listOf(
                    "transaction_type", "amount", "primary_amount", "currency",
                    "transaction_time", "payment_source", "payment_method", "category",
                    "purpose_info", "merchant_name", "product_name", "transaction_status",
                    "remarks", "detail",
                ),
                searchColumns = listOf(
                    "payment_source", "payment_method", "category", "purpose_info",
                    "merchant_name", "product_name", "transaction_status", "remarks", "detail",
                ),
            ),
            DetailSpec(
                outputName = "schedules",
                table = "schedule_todos",
                columns = listOf(
                    "type", "sub_type", "time", "content", "status", "start_time",
                    "end_time", "address", "remark",
                ),
                searchColumns = listOf("type", "sub_type", "time", "content", "address", "remark"),
            ),
            DetailSpec(
                outputName = "pickup_codes",
                table = "pickup_codes",
                columns = listOf(
                    "type", "pickup_code", "brand", "product_name", "merchant_name",
                    "order_status", "order_time", "wait_time", "create_time",
                ),
                searchColumns = listOf(
                    "type", "pickup_code", "brand", "product_name", "merchant_name",
                    "order_status", "order_time",
                ),
            ),
            DetailSpec(
                outputName = "shipments",
                table = "shipments",
                columns = listOf(
                    "type", "code", "address", "courier_code", "order", "status",
                    "save_time", "create_time", "update_time",
                ),
                searchColumns = listOf(
                    "type", "code", "address", "courier_code", "order", "status", "save_time",
                ),
            ),
            DetailSpec(
                outputName = "personal_info",
                table = "personal_infos",
                columns = listOf("type", "sub_type", "details", "extra", "not_reminder"),
                searchColumns = listOf("type", "sub_type", "details", "extra"),
            ),
            DetailSpec(
                outputName = "places",
                table = "memory_address",
                linkColumn = "memory_id",
                columns = listOf(
                    "category", "sub_type", "name", "address", "full_address", "country",
                    "province", "city", "district", "location", "longitude", "latitude",
                    "reason", "insight",
                ),
                searchColumns = listOf(
                    "category", "sub_type", "name", "address", "full_address", "country",
                    "province", "city", "district", "location", "reason", "insight",
                ),
            ),
            DetailSpec(
                outputName = "attachments",
                table = "attachments",
                columns = listOf(
                    "attachment_id", "media_type", "path", "uri", "width", "height",
                    "text", "ocr_text", "caption",
                ),
                searchColumns = listOf("path", "uri", "text", "ocr_text", "caption"),
            ),
        )
    }
}
