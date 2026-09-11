package com.bnm.lab

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform