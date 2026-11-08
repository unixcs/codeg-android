package app.codeg.android.feature.sessions

import androidx.annotation.StringRes
import app.codeg.android.R

/**
 * 批v168: an action bindable to a session row's horizontal swipe (Settings →
 * General → 左滑/右滑动作). The catalog mirrors the long-press menu's items —
 * a swipe is just a faster route into the same handlers, so no new behaviour
 * lives here.
 *
 * The persisted setting is the raw [id] string (the datastore layer stays free
 * of feature types); [from] is the single read-side resolver and maps the
 * deliberate "none" binding (and anything unknown) to null — a null binding
 * means that swipe direction is OFF for the row.
 */
enum class SessionSwipeAction(val id: String) {
    /** 加入任务板 / 移出看板 — the owner's core one-tap workflow. */
    BOARD("board"),

    /** 复制会话引用 — clipboard only, safe everywhere. */
    COPY("copy"),

    /** 置顶 / 取消置顶 — only offered on surfaces that can pin. */
    PIN("pin"),

    /** @添加到会话 — navigates to a new composer, so never a default. */
    HANDOFF("handoff"),

    /** 重命名 — opens the existing pending-state dialog. */
    RENAME("rename"),

    /** 添加到项目 — opens the existing pending-state sheet. */
    ADD_PROJECT("add_project"),

    /** 删除 — destructive: firing it still lands in the confirm dialog. */
    DELETE("delete"),
    ;

    companion object {
        /** The stored "direction disabled" value — resolves to a null binding. */
        const val NONE_ID = "none"

        /** Single read-side resolver: unknown / blank / "none" → null. */
        fun from(id: String?): SessionSwipeAction? = entries.firstOrNull { it.id == id }

        /** The persisted ring order used by 「悬浮球循环切绑定」 and the settings
         *  picker: "none" first, then the catalog order. */
        val RING: List<String> = listOf(NONE_ID) + entries.map { it.id }
    }
}

/** 批v171: shared action→string-res map — the settings picker and the floating
 *  ball's cycle toast speak the same names (was a private fn in GeneralScreen). */
@StringRes
fun swipeActionLabelRes(actionId: String): Int? = when (SessionSwipeAction.from(actionId)) {
    null -> R.string.swipe_action_none
    SessionSwipeAction.BOARD -> R.string.board_conv_add
    SessionSwipeAction.COPY -> R.string.session_copy_reference
    SessionSwipeAction.PIN -> R.string.session_pin
    SessionSwipeAction.HANDOFF -> R.string.session_add_to_session
    SessionSwipeAction.RENAME -> R.string.session_rename
    SessionSwipeAction.ADD_PROJECT -> R.string.session_add_project
    SessionSwipeAction.DELETE -> R.string.common_delete
}
