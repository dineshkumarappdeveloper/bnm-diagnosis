package com.bnm.lab.instruments

// Analyzers plug into the lab PC; an Android seat can show the checklist but not read the PC.
actual fun platformLinkEnvironment(): LinkEnvironment = UnknownLinkEnvironment
