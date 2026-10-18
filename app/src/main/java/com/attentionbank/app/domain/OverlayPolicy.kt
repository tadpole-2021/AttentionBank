package com.attentionbank.app.domain

internal enum class OverlayAction {
    SHOW,
    HIDE,
    KEEP,
}

internal fun overlayAction(
    visible: Boolean,
    screenAvailable: Boolean,
    currentPackage: String?,
    checkedPackage: String?,
    shouldBlock: Boolean,
): OverlayAction {
    if (!screenAvailable) return if (visible) OverlayAction.HIDE else OverlayAction.KEEP
    // Missing windows and outdated queries are not evidence that the user left the app.
    if (currentPackage == null || checkedPackage != currentPackage) return OverlayAction.KEEP
    return when {
        shouldBlock && !visible -> OverlayAction.SHOW
        !shouldBlock && visible -> OverlayAction.HIDE
        else -> OverlayAction.KEEP
    }
}
