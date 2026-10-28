# The Play Store binds to these by interface descriptor. The generated stubs carry the descriptor
# as a string, so renaming would be harmless, but LVL's own guidance keeps them and so do we.
-keep class com.android.vending.licensing.ILicensingService { *; }
-keep class com.android.vending.licensing.ILicensingService$* { *; }
-keep class com.android.vending.licensing.ILicenseResultListener { *; }
-keep class com.android.vending.licensing.ILicenseResultListener$* { *; }
