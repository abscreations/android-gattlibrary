package com.gmail.absolutebollockscreations.gattlibrary.peripheral

enum class FtmsClientConnectionStatus { READY, CONNECTED, DISCONNECTED }
enum class FtmsClientControlStatus { NOT_CONTROLLABLE, CONTROLLABLE, CLIENT_IS_CONTROLLING }

fun FtmsPeripheralController.PeripheralStatus.clientConnectionStatus(): FtmsClientConnectionStatus =
    when {
        clientLinkConnected -> FtmsClientConnectionStatus.CONNECTED
        clientAdvertising && !clientEverConnected -> FtmsClientConnectionStatus.READY
        else -> FtmsClientConnectionStatus.DISCONNECTED
    }

fun FtmsPeripheralController.PeripheralStatus.clientControlStatus(): FtmsClientControlStatus =
    when {
        clientControlReceived -> FtmsClientControlStatus.CLIENT_IS_CONTROLLING
        clientControllable -> FtmsClientControlStatus.CONTROLLABLE
        else -> FtmsClientControlStatus.NOT_CONTROLLABLE
    }
