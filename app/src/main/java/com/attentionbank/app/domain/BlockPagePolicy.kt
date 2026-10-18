package com.attentionbank.app.domain

internal enum class BlockPageAction {
    LAUNCH,
    HOME,
    RESET,
    WAIT,
}

internal fun blockPageAction(
    screenAvailable: Boolean,
    currentPackage: String?,
    checkedPackage: String?,
    shouldBlock: Boolean,
    pendingPackage: String?,
    elapsedMillis: Long,
    fallbackSent: Boolean,
): BlockPageAction {
    if (!screenAvailable) return BlockPageAction.RESET
    if (currentPackage == null || currentPackage != checkedPackage) return BlockPageAction.WAIT
    if (!shouldBlock) return BlockPageAction.RESET
    if (pendingPackage != currentPackage) return BlockPageAction.LAUNCH
    // startActivity can be silently denied by a ROM; do not repeatedly steal focus.
    if (!fallbackSent && elapsedMillis >= 1500L) return BlockPageAction.HOME
    return BlockPageAction.WAIT
}
