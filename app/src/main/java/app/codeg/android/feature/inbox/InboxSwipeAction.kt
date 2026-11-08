package app.codeg.android.feature.inbox

import androidx.annotation.StringRes
import app.codeg.android.R

/**
 * 批v171 N7: an action bindable to an inbox idea card's horizontal swipe — the
 * same catalog as the card's long-press menu (派发/编辑/复制/追加到会话/删除);
 * a swipe is just a faster route into the same handlers, so no new behaviour
 * lives here.
 *
 * Same contract as the sessions package' SessionSwipeAction: the persisted
 * setting is the raw [id] string (the datastore layer stays free of feature
 * types), [from] is the single read-side resolver, and the deliberate "none"
 * binding (or anything unknown) resolves to null — a null binding means that
 * swipe direction is OFF for the card.
 */
enum class InboxSwipeAction(val id: String) {
    /** 派发到设备 — opens the existing dispatch sheet. */
    DISPATCH("dispatch"),

    /** 编辑 — opens the idea editor. */
    EDIT("edit"),

    /** 复制内容 — clipboard only, safe everywhere. */
    COPY("copy"),

    /** 追加到会话 — opens the session picker (批v162 反向快链). */
    APPEND("append"),

    /** 删除 — destructive: firing it still lands in the confirm dialog. */
    DELETE("delete"),
    ;

    companion object {
        /** The stored "direction disabled" value — resolves to a null binding. */
        const val NONE_ID = "none"

        /** Single read-side resolver: unknown / blank / "none" → null. */
        fun from(id: String?): InboxSwipeAction? = entries.firstOrNull { it.id == id }

        /** The settings picker order: "none" first, then the catalog order. */
        val RING: List<String> = listOf(NONE_ID) + entries.map { it.id }
    }
}

/** 批v171: shared action→string-res map — the settings picker and the swipe
 *  reveal pane speak the same names. */
@StringRes
fun inboxSwipeActionLabelRes(actionId: String): Int? = when (InboxSwipeAction.from(actionId)) {
    null -> R.string.swipe_action_none
    InboxSwipeAction.DISPATCH -> R.string.inbox_swipe_action_dispatch
    InboxSwipeAction.EDIT -> R.string.inbox_swipe_action_edit
    InboxSwipeAction.COPY -> R.string.inbox_swipe_action_copy
    InboxSwipeAction.APPEND -> R.string.inbox_swipe_action_append
    InboxSwipeAction.DELETE -> R.string.common_delete
}
