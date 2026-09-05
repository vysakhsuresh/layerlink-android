package com.layerbit.core.webrtc

/**
 * Capture/encode trade-off chosen before a broadcast starts. [HIGH] is the app's original,
 * always-on behavior (full device resolution, unset/adaptive bitrate) and stays the default;
 * [DATA_SAVER] halves capture resolution, lowers frame rate, and caps the encoder's bitrate,
 * for weak Wi-Fi or mobile data.
 */
enum class QualityProfile {
    HIGH,
    DATA_SAVER
}
