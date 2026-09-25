package com.bnm.lab.remote

/**
 * Two spellings of the same serial port.
 *
 * An instrument row holds the name jSerialComm reported when the bench picked
 * the port from the list — `COM3` on Windows, `ttyUSB0` on Linux, whose device
 * file is `/dev/ttyUSB0`. The engineer, typing a port into `instruments.probe`
 * or `instruments.set_config`, writes whatever the analyzer's manual or `ls
 * /dev` showed. `SerialPort.getCommPort` happily accepts every one of those
 * spellings, so comparing the raw strings lets a guard be walked straight
 * past: `{"serial_port":"/dev/ttyUSB0"}` would open the exact port the
 * analyzer named `ttyUSB0` is listening on, and a link drops mid-run.
 *
 * So both sides are reduced before they are compared. Folding case is
 * deliberate over-matching: no lab has two ports whose names differ only in
 * case, and the wrong answer here should be "refused, disable the analyzer
 * first" rather than "took the port away from a running analyzer".
 */
object SerialPortNames {

    /** Is [a] the same port as [b]? False if either is missing. */
    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }

    /** `\\.\COM3`, `COM3`, `/dev/ttyUSB0` → `com3`, `com3`, `ttyusb0`. Null when blank. */
    fun normalize(name: String?): String? {
        val trimmed = name?.trim()?.ifEmpty { null } ?: return null
        return trimmed.removePrefix("\\\\.\\").removePrefix("/dev/").lowercase()
    }
}
