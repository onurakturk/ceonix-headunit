package com.andrerinas.headunitrevived.license

/**
 * What the lock screen and the gates see, in both flavours. The Play flavour maps the licensing
 * module's state onto this; the GitHub flavour is always [LICENSED].
 */
enum class LicenseStatus {
    LICENSED,

    /** No verified licence on this device yet: one online check is needed. */
    UNVERIFIED,

    /** Google said, signed, that this account does not own the app. */
    NOT_LICENSED,

    /** The APK has been re-signed: not the copy Google Play delivered. */
    TAMPERED,
}
