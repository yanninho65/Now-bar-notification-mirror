package com.yann.nowbarmirror.settings

/**
 * How a given source app's notifications should be mirrored.
 * Ordinal order matters: it maps 1:1 to the spinner position in the
 * app-selection screen (NONE=0, LATEST=1, ALL=2).
 */
enum class MirrorMode {
    NONE,
    LATEST,
    ALL
}
