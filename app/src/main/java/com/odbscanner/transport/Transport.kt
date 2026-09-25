package com.odbscanner.transport

import java.io.InputStream
import java.io.OutputStream

/** Byte pipe to an ELM327 adapter. [open] blocks — call it from an IO thread. */
interface Transport {
    val name: String
    val input: InputStream
    val output: OutputStream
    fun open()
    fun close()
}
