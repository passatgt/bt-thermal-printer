# The Bluetooth Classic fallback reflects on a framework method
# (BluetoothDevice.createRfcommSocket) rather than our own code, so R8 has
# nothing to keep for it. Manifest-declared components - MainActivity,
# PrintPdfActivity and ThermalPrintService - are kept automatically by AGP,
# and Compose ships its own consumer rules.
#
# Keep line numbers so a stack trace from a release build is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
