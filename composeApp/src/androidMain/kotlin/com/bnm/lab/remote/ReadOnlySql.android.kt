package com.bnm.lab.remote

/** No remote support session on Android, so no read-only query connection either. */
actual fun platformReadOnlySql(): ReadOnlySql? = null
