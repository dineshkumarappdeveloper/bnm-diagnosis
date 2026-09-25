package com.bnm.lab.remote

/** No remote support session on iOS, so no read-only query connection either. */
actual fun platformReadOnlySql(): ReadOnlySql? = null
