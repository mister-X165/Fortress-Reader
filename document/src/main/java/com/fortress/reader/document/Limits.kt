package com.fortress.reader.document

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

/** Aborts when more than [remaining] bytes are read (decompression-bomb guard). */
internal class LimitedStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && --remaining < 0) throw IOException("Decompressed size limit exceeded")
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) { remaining -= n; if (remaining < 0) throw IOException("Decompressed size limit exceeded") }
        return n
    }
}

/** Consumes the current element (start tag already read) including its end tag. */
internal fun skipElement(p: XmlPullParser) {
    var depth = 1
    while (depth > 0) {
        when (p.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}
