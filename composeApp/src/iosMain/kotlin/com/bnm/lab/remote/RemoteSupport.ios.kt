package com.bnm.lab.remote

/** No remote support session on iOS — the desktop lab PC is what BNM services. */
actual fun platformRemoteSupportController(): RemoteSupportController? = null
