package com.bnm.lab.remote

/** The desktop engine — a lab PC is what BNM services. */
actual fun platformRemoteSupportController(): RemoteSupportController? = RemoteSupportService.instance
