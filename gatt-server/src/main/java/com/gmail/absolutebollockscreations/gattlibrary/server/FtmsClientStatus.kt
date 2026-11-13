package com.gmail.absolutebollockscreations.gattlibrary.server

public enum class FtmsClientConnectionStatus { READY, CONNECTED, DISCONNECTED }
public enum class FtmsClientControlStatus { NOT_CONTROLLABLE, CONTROLLABLE, CLIENT_IS_CONTROLLING }

public fun FtmsPeripheralController.PeripheralStatus.clientConnectionStatus(): FtmsClientConnectionStatus =
    when {
        clientLinkConnected -> FtmsClientConnectionStatus.CONNECTED
        clientAdvertising && !clientEverConnected -> FtmsClientConnectionStatus.READY
        else -> FtmsClientConnectionStatus.DISCONNECTED
    }

public fun FtmsPeripheralController.PeripheralStatus.clientControlStatus(): FtmsClientControlStatus =
    when {
        clientControlReceived -> FtmsClientControlStatus.CLIENT_IS_CONTROLLING
        clientControllable -> FtmsClientControlStatus.CONTROLLABLE
        else -> FtmsClientControlStatus.NOT_CONTROLLABLE
    }
