package com.mcarplay.app.usb

import com.mcarplay.app.airplay.BinaryPlist
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lockdown 所需的最小 plist 子集。
 *
 * 线上格式是 4 字节大端 payload 长度加 XML 或 bplist00 文档。响应保持 XML；请求兼容
 * macOS usbmuxd 后续使用的 binary plist。XML 解析禁用外部实体和外部 DTD。
 */
object LockdownPlist {
    const val MAX_MESSAGE_BYTES = 1024 * 1024

    sealed interface Value {
        data class Dictionary(
            val entries: Map<String, Value>,
        ) : Value

        data class Text(
            val value: String,
        ) : Value

        data class Integer(
            val value: Long,
        ) : Value

        data class Boolean(
            val value: kotlin.Boolean,
        ) : Value

        data class Real(
            val value: Double,
        ) : Value

        data class Array(
            val values: List<Value>,
        ) : Value

        data object Null : Value

        data class Data(
            val bytes: ByteArray,
        ) : Value {
            override fun equals(other: Any?): kotlin.Boolean = this === other || other is Data && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    fun encodeFrame(message: Value.Dictionary): ByteArray {
        val xml = encode(message).toByteArray(StandardCharsets.UTF_8)
        require(xml.isNotEmpty() && xml.size <= MAX_MESSAGE_BYTES) { "Lockdown plist is outside the size limit" }
        return ByteArray(4 + xml.size).also {
            UsbMuxProtocol.putU32(it, 0, xml.size)
            xml.copyInto(it, 4)
        }
    }

    fun parseFrame(frame: ByteArray): Value.Dictionary {
        require(frame.size >= 4) { "Lockdown plist frame is shorter than its length prefix" }
        val length = UsbMuxProtocol.readU32(frame, 0)
        require(length in 1..MAX_MESSAGE_BYTES) { "invalid Lockdown plist length $length" }
        require(frame.size == length + 4) { "Lockdown plist length $length does not match ${frame.size - 4} bytes" }
        return parsePayload(frame.copyOfRange(4, frame.size))
    }

    fun readFrame(input: InputStream): Value.Dictionary {
        val header = readFully(input, 4)
        val length = UsbMuxProtocol.readU32(header, 0)
        require(length in 1..MAX_MESSAGE_BYTES) { "invalid Lockdown plist length $length" }
        return parsePayload(readFully(input, length))
    }

    fun writeFrame(
        output: OutputStream,
        message: Value.Dictionary,
    ) {
        output.write(encodeFrame(message))
        output.flush()
    }

    fun encode(message: Value.Dictionary): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            append("<plist version=\"1.0\">")
            appendValue(message)
            append("</plist>")
        }

    private fun StringBuilder.appendValue(value: Value) {
        when (value) {
            is Value.Dictionary -> {
                append("<dict>")
                value.entries.forEach { (key, child) ->
                    append("<key>")
                    append(xmlEscape(key))
                    append("</key>")
                    appendValue(child)
                }
                append("</dict>")
            }
            is Value.Text -> {
                append("<string>")
                append(xmlEscape(value.value))
                append("</string>")
            }
            is Value.Integer -> {
                append("<integer>")
                append(value.value)
                append("</integer>")
            }
            is Value.Boolean -> append(if (value.value) "<true/>" else "<false/>")
            is Value.Real -> {
                append("<real>")
                append(value.value)
                append("</real>")
            }
            is Value.Array -> {
                append("<array>")
                value.values.forEach { appendValue(it) }
                append("</array>")
            }
            Value.Null -> error("XML plist cannot encode null")
            is Value.Data -> {
                append("<data>")
                append(Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII)).encodeToString(value.bytes))
                append("</data>")
            }
        }
    }

    private fun parsePayload(bytes: ByteArray): Value.Dictionary {
        if (bytes.startsWith(BINARY_PLIST_MAGIC)) {
            val root =
                BinaryPlist.parse(bytes) as? Map<*, *>
                    ?: error("Binary Lockdown plist root value must be a dictionary")
            return fromBinaryValue(root) as Value.Dictionary
        }
        return parseXml(bytes)
    }

    private fun fromBinaryValue(value: Any?): Value =
        when (value) {
            is Map<*, *> ->
                Value.Dictionary(
                    linkedMapOf<String, Value>().apply {
                        value.forEach { (key, child) ->
                            require(key is String) { "Binary Lockdown plist dictionary key must be a string" }
                            put(key, fromBinaryValue(child))
                        }
                    },
                )
            is List<*> -> Value.Array(value.map(::fromBinaryValue))
            is String -> Value.Text(value)
            is Int -> Value.Integer(value.toLong())
            is Long -> Value.Integer(value)
            is kotlin.Boolean -> Value.Boolean(value)
            is Double -> Value.Real(value)
            is ByteArray -> Value.Data(value)
            null -> Value.Null
            else -> error("Unsupported binary Lockdown plist value ${value.javaClass.simpleName}")
        }

    private fun parseXml(bytes: ByteArray): Value.Dictionary {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
                setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
                setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val plist = document.documentElement
        require(plist.tagName == "plist") { "Lockdown plist root must be <plist>" }
        val root = firstElementChild(plist) ?: error("Lockdown plist contains no root value")
        require(firstElementChild(root, next = true) == null) { "Lockdown plist must contain exactly one root value" }
        return parseElement(root) as? Value.Dictionary
            ?: error("Lockdown plist root value must be a dictionary")
    }

    private fun parseElement(element: Element): Value =
        when (element.tagName) {
            "dict" -> parseDictionary(element)
            "string" -> Value.Text(element.textContent.orEmpty())
            "integer" ->
                Value.Integer(
                    element.textContent
                        .orEmpty()
                        .trim()
                        .toLongOrNull() ?: error("invalid Lockdown integer"),
                )
            "true" -> {
                require(element.textContent.orEmpty().isBlank()) { "Lockdown true value contains text" }
                Value.Boolean(true)
            }
            "false" -> {
                require(element.textContent.orEmpty().isBlank()) { "Lockdown false value contains text" }
                Value.Boolean(false)
            }
            "data" -> {
                val encoded = element.textContent.orEmpty().filterNot(Char::isWhitespace)
                Value.Data(Base64.getDecoder().decode(encoded))
            }
            "real" ->
                Value.Real(
                    element.textContent
                        .orEmpty()
                        .trim()
                        .toDoubleOrNull() ?: error("invalid Lockdown real"),
                )
            "array" -> parseArray(element)
            else -> error("unsupported Lockdown plist element <${element.tagName}>")
        }

    private fun parseArray(element: Element): Value.Array {
        val values = ArrayList<Value>()
        var child = firstElementChild(element)
        while (child != null) {
            values += parseElement(child)
            child = firstElementChild(child, next = true)
        }
        return Value.Array(values)
    }

    private fun parseDictionary(element: Element): Value.Dictionary {
        val entries = linkedMapOf<String, Value>()
        var child = firstElementChild(element)
        while (child != null) {
            require(child.tagName == "key") { "Lockdown dictionary expected <key>, got <${child.tagName}>" }
            val key = child.textContent.orEmpty()
            require(key !in entries) { "duplicate Lockdown dictionary key '$key'" }
            val valueElement = firstElementChild(child, next = true) ?: error("Lockdown dictionary key '$key' has no value")
            entries[key] = parseElement(valueElement)
            child = firstElementChild(valueElement, next = true)
        }
        return Value.Dictionary(entries)
    }

    private fun firstElementChild(
        parent: Node,
        next: Boolean = false,
    ): Element? {
        var child = if (next) parent.nextSibling else parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) return child as Element
            child = child.nextSibling
        }
        return null
    }

    private fun readFully(
        input: InputStream,
        length: Int,
    ): ByteArray {
        require(length in 0..MAX_MESSAGE_BYTES)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("Lockdown stream ended after $offset of $length bytes")
            if (count == 0) continue
            offset += count
        }
        return bytes
    }

    private fun xmlEscape(value: String): String =
        buildString(value.length) {
            value.forEach {
                append(
                    when (it) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&apos;"
                        else -> it
                    },
                )
            }
        }

    private fun DocumentBuilderFactory.setFeatureIfSupported(
        name: String,
        value: Boolean,
    ) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.setAttributeIfSupported(
        name: String,
        value: String,
    ) {
        runCatching { setAttribute(name, value) }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): kotlin.Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val BINARY_PLIST_MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)
}
