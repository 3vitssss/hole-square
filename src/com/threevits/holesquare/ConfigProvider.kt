package com.threevits.holesquare

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 把配置暴露给 SystemUI 里的 hook 进程。
 *
 * 只读，只返回一行四列（形状 id、边长 px、X 偏移、Y 偏移）。
 * 导出是必须的 —— 查询方是 SystemUI，跟本 app 不同 uid、不同签名，
 * 拿不到任何自定义权限。里面没有敏感信息。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = arrayOf(
            Config.COL_SELECTION, Config.COL_SIZE, Config.COL_OFFSET_X, Config.COL_OFFSET_Y,
            Config.COL_SVG
        )
        val ctx = context ?: return MatrixCursor(columns)
        val config = Config.read(ctx)
        return MatrixCursor(columns).apply {
            addRow(
                arrayOf<Any>(
                    config.selection,
                    config.sizePx,
                    config.offsetXPx,
                    config.offsetYPx,
                    // 内置形状时是空串 —— 查询方靠这个区分内置和自定义
                    config.svg.take(Config.MAX_SVG_CHARS)
                )
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val AUTHORITY = Config.AUTHORITY
    }
}
