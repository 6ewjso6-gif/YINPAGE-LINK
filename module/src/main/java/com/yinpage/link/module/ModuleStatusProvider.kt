package com.yinpage.link.module

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * ============================================================================
 *  模块状态查询 Provider
 * ============================================================================
 *  让免 root 的独立 App（`:app`）可以查询"LSPosed 模块是否已安装并生效"，
 *  从而在界面上给出准确提示（而不是让用户猜）。
 *
 *  authority: `com.yinpage.link.module.status`
 *  查询任意路径都返回一行：{ active, version }
 * ============================================================================
 */
class ModuleStatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // 如实上报：只有至少一个目标进程真正装上了 Hook 才报"已激活"，
        // 避免模块只是安装了、但 Hook 全部失败时仍向 App 谎报 active=1。
        val active = ModuleConfigStore.hookResults(context!!).values.any { it }
        val cursor = MatrixCursor(arrayOf("active", "version", "module_id"))
        cursor.addRow(arrayOf(if (active) 1 else 0, BuildConfig.VERSION_NAME, "yinpage_link"))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.yinpage.module.status"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
