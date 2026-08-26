package com.organicmoto.maps.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Database file name; exposed so tests can reset state between runs. */
internal const val SAVED_ROUTES_DB_NAME = "saved_routes.db"

/**
 * [SavedRouteStore] backed by device SQLite through [SQLiteOpenHelper].
 *
 * Schema v1: one row per saved route plus a child comments table with an
 * `ON DELETE CASCADE` foreign key, so deleting a route removes its comments
 * in the same statement. Foreign keys are enabled per-connection in
 * [onConfigure]; without that SQLite leaves them off and the cascade never
 * fires.
 */
class SqlSavedRouteStore(context: Context) : SavedRouteStore {

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, SAVED_ROUTES_DB_NAME, null, DB_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_ROUTES)
            db.execSQL(SQL_CREATE_COMMENTS)
        }

        // Version 1 is the first released schema; upgrades land here.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val helper = Helper(context.applicationContext)

    override fun insertRoute(route: SavedRoute): Long {
        val values = ContentValues(13).apply {
            put(FROM_NAME, route.fromName)
            put(FROM_LAT, route.fromLat)
            put(FROM_LON, route.fromLon)
            put(TO_NAME, route.toName)
            put(TO_LAT, route.toLat)
            put(TO_LON, route.toLon)
            put(DISTANCE, route.distanceMeters)
            put(DURATION, route.durationMillis)
            put(COMPLEXITY, route.complexity.toDouble())
            put(ROAD_SHARE, route.maxRoadSharePercent.toDouble())
            put(BLOCK_UNPAVED, if (route.blockUnpaved) 1L else 0L)
            put(GEOMETRY, route.geometry)
            put(CREATED_AT, route.createdAtMillis)
        }
        return requireInserted(helper.writableDatabase.insertOrThrow(TABLE_ROUTES, null, values))
    }

    override fun summaries(): List<SavedRouteSummary> {
        val db = helper.readableDatabase
        db.rawQuery(SQL_SUMMARIES, emptyArray()).use { cursor ->
            val indices = SummaryColumns(cursor)
            val results = ArrayList<SavedRouteSummary>(cursor.count)
            while (cursor.moveToNext()) {
                results.add(
                    SavedRouteSummary(
                        route = SavedRoute(
                            id = cursor.getLong(indices.id),
                            fromName = cursor.getString(indices.fromName),
                            fromLat = cursor.getDouble(indices.fromLat),
                            fromLon = cursor.getDouble(indices.fromLon),
                            toName = cursor.getString(indices.toName),
                            toLat = cursor.getDouble(indices.toLat),
                            toLon = cursor.getDouble(indices.toLon),
                            distanceMeters = cursor.getDouble(indices.distance),
                            durationMillis = cursor.getLong(indices.duration),
                            complexity = cursor.getDouble(indices.complexity).toFloat(),
                            maxRoadSharePercent = cursor.getDouble(indices.roadShare).toFloat(),
                            blockUnpaved = cursor.getLong(indices.blockUnpaved) != 0L,
                            geometry = cursor.getString(indices.geometry),
                            createdAtMillis = cursor.getLong(indices.createdAt),
                        ),
                        commentCount = cursor.getInt(indices.commentCount),
                    )
                )
            }
            return results
        }
    }

    override fun deleteRoute(id: Long) {
        helper.writableDatabase.delete(TABLE_ROUTES, "$ID = ?", arrayOf(id.toString()))
    }

    override fun comments(routeId: Long): List<SavedRouteComment> {
        val db = helper.readableDatabase
        db.query(
            TABLE_COMMENTS,
            arrayOf(ID, ROUTE_ID, TEXT, CREATED_AT),
            "$ROUTE_ID = ?",
            arrayOf(routeId.toString()),
            null,
            null,
            ORDER_OLDEST_FIRST,
        ).use { cursor ->
            val indices = CommentColumns(cursor)
            val results = ArrayList<SavedRouteComment>(cursor.count)
            while (cursor.moveToNext()) {
                results.add(
                    SavedRouteComment(
                        id = cursor.getLong(indices.id),
                        routeId = cursor.getLong(indices.routeId),
                        text = cursor.getString(indices.text),
                        createdAtMillis = cursor.getLong(indices.createdAt),
                    )
                )
            }
            return results
        }
    }

    override fun insertComment(comment: SavedRouteComment): Long {
        val db = helper.writableDatabase
        // Check first with a clear error message; the FK would abort with a
        // bare constraint violation either way.
        db.query(
            TABLE_ROUTES,
            arrayOf(ID),
            "$ID = ?",
            arrayOf(comment.routeId.toString()),
            null,
            null,
            null,
        ).use {
            require(it.moveToFirst()) {
                "Cannot comment on route ${comment.routeId}: no such saved route"
            }
        }
        val values = ContentValues(3).apply {
            put(ROUTE_ID, comment.routeId)
            put(TEXT, comment.text)
            put(CREATED_AT, comment.createdAtMillis)
        }
        return requireInserted(db.insertOrThrow(TABLE_COMMENTS, null, values))
    }

    /** Turns a rowid of 0 or less (insert failure) into a loud error. */
    private fun requireInserted(rowId: Long): Long {
        check(rowId > 0L) { "Insert into saved routes failed" }
        return rowId
    }

    /** Column positions for [SQL_SUMMARIES], resolved once per cursor. */
    private class SummaryColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow("$TABLE_ROUTES.$ID")
        val fromName = cursor.getColumnIndexOrThrow(FROM_NAME)
        val fromLat = cursor.getColumnIndexOrThrow(FROM_LAT)
        val fromLon = cursor.getColumnIndexOrThrow(FROM_LON)
        val toName = cursor.getColumnIndexOrThrow(TO_NAME)
        val toLat = cursor.getColumnIndexOrThrow(TO_LAT)
        val toLon = cursor.getColumnIndexOrThrow(TO_LON)
        val distance = cursor.getColumnIndexOrThrow(DISTANCE)
        val duration = cursor.getColumnIndexOrThrow(DURATION)
        val complexity = cursor.getColumnIndexOrThrow(COMPLEXITY)
        val roadShare = cursor.getColumnIndexOrThrow(ROAD_SHARE)
        val blockUnpaved = cursor.getColumnIndexOrThrow(BLOCK_UNPAVED)
        val geometry = cursor.getColumnIndexOrThrow(GEOMETRY)
        val createdAt = cursor.getColumnIndexOrThrow(CREATED_AT)
        val commentCount = cursor.getColumnIndexOrThrow(COMMENT_COUNT)
    }

    private class CommentColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(ID)
        val routeId = cursor.getColumnIndexOrThrow(ROUTE_ID)
        val text = cursor.getColumnIndexOrThrow(TEXT)
        val createdAt = cursor.getColumnIndexOrThrow(CREATED_AT)
    }

    private companion object {
        const val DB_VERSION = 1

        const val TABLE_ROUTES = "saved_routes"
        const val TABLE_COMMENTS = "saved_route_comments"

        const val ID = "id"
        const val ROUTE_ID = "route_id"
        const val FROM_NAME = "from_name"
        const val FROM_LAT = "from_lat"
        const val FROM_LON = "from_lon"
        const val TO_NAME = "to_name"
        const val TO_LAT = "to_lat"
        const val TO_LON = "to_lon"
        const val DISTANCE = "distance_meters"
        const val DURATION = "duration_millis"
        const val COMPLEXITY = "complexity"
        const val ROAD_SHARE = "max_road_share_percent"
        const val BLOCK_UNPAVED = "block_unpaved"
        const val GEOMETRY = "geometry"
        const val CREATED_AT = "created_at_millis"
        const val TEXT = "text"
        const val COMMENT_COUNT = "comment_count"

        const val ORDER_OLDEST_FIRST = "$CREATED_AT ASC, $ID ASC"

        val SQL_CREATE_ROUTES =
            """
            CREATE TABLE $TABLE_ROUTES (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $FROM_NAME TEXT NOT NULL,
                $FROM_LAT REAL NOT NULL,
                $FROM_LON REAL NOT NULL,
                $TO_NAME TEXT NOT NULL,
                $TO_LAT REAL NOT NULL,
                $TO_LON REAL NOT NULL,
                $DISTANCE REAL NOT NULL,
                $DURATION INTEGER NOT NULL,
                $COMPLEXITY REAL NOT NULL,
                $ROAD_SHARE REAL NOT NULL,
                $BLOCK_UNPAVED INTEGER NOT NULL,
                $GEOMETRY TEXT NOT NULL,
                $CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()

        val SQL_CREATE_COMMENTS =
            """
            CREATE TABLE $TABLE_COMMENTS (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $ROUTE_ID INTEGER NOT NULL REFERENCES $TABLE_ROUTES($ID) ON DELETE CASCADE,
                $TEXT TEXT NOT NULL,
                $CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()

        /** One query feeds the menu: every route plus its comment count. */
        val SQL_SUMMARIES =
            """
            SELECT r.*, COUNT(c.$ID) AS $COMMENT_COUNT
            FROM $TABLE_ROUTES r
            LEFT JOIN $TABLE_COMMENTS c ON c.$ROUTE_ID = r.$ID
            GROUP BY r.$ID
            ORDER BY r.$CREATED_AT DESC, r.$ID DESC
            """.trimIndent()
    }
}
