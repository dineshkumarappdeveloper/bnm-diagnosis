package com.bnm.lab.instruments

// iOS has no serial ports or firewall to read; analyzers talk to the lab PC.
actual fun platformLinkEnvironment(): LinkEnvironment = UnknownLinkEnvironment
