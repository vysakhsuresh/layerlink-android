package com.layerbit.layerlink.util

import java.security.SecureRandom

/** Generates a short, URL-safe session id, e.g. for `?session=<id>` viewer links. */
object SessionIdGenerator {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun generate(length: Int = 6): String =
        (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}
