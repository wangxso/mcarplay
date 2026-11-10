package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSourceTest {
    @Test
    fun commentsAreDropped() {
        val scan =
            KotlinSource.scan(
                """
                // import com.mcarplay.app.SessionController
                /** 见 SessionController.onSenderLinkClosed */
                /* 外层 /* 嵌套 SessionController */ 仍在注释里 SessionController */
                val a = 1
                """.trimIndent(),
            )
        assertFalse(scan.code.contains("SessionController"))
        assertTrue(scan.code.contains("val a = 1"))
    }

    @Test
    fun urlsInsideStringsAreNotLineComments() {
        val scan = KotlinSource.scan("val u = \"https://x.example/a\"; val after = SessionController")
        assertEquals(listOf("https://x.example/a"), scan.strings)
        assertTrue(scan.code.contains("val after = SessionController"))
    }

    @Test
    fun templatesSplitStringsAndKeepExpressionsAsCode() {
        val scan = KotlinSource.scan("log(\"VIDEO: 转码统计（已发 \$framesSent 帧）\${if (ok) \"是\" else \"否\"} 结束\")")
        assertEquals(listOf("VIDEO: 转码统计（已发 ", " 帧）", "是", "否", " 结束"), scan.strings)
        assertTrue(scan.code.contains("framesSent"))
        assertTrue(scan.code.contains("if (ok)"))
        assertFalse(scan.code.contains("VIDEO"))
    }

    @Test
    fun bracesInsideTemplatesDoNotEndTheTemplateEarly() {
        val scan = KotlinSource.scan("val s = \"a\${list.map { it + 1 }.size}b\"; val tail = PassatReturnStrategy")
        assertEquals(listOf("a", "b"), scan.strings)
        assertTrue(scan.code.contains("list.map { it + 1 }.size"))
        assertTrue(scan.code.contains("val tail = PassatReturnStrategy"))
    }

    @Test
    fun rawStringsAndCharLiteralsAreHandled() {
        val quote = "\"\"\""
        val scan = KotlinSource.scan("val r = ${quote}com.mcarplay.app.ui \"q\" \\d$quote; val c = '\"'; val e = '\\''; val n = next")
        assertEquals(listOf("com.mcarplay.app.ui \"q\" \\d"), scan.strings)
        assertFalse(scan.code.contains("com.mcarplay.app.ui"))
        assertTrue(scan.code.contains("val n = next"))
    }

    @Test
    fun escapedQuotesStayInsideTheString() {
        val scan = KotlinSource.scan("val s = \"say \\\"hi\\\" // not a comment\"; val k = 2")
        assertEquals(listOf("say \"hi\" // not a comment"), scan.strings)
        assertTrue(scan.code.contains("val k = 2"))
    }

    @Test
    fun identifierWordsSplitCamelCaseAndUpperSnakeCase() {
        val words = KotlinSource.identifierWords("PassatReturnStrategy.CLASSIC_MIB3 + AudioSender + xmlHTTPRequest + id7Profile")
        assertTrue(
            words.containsAll(listOf("passat", "return", "strategy", "classic", "mib3", "audio", "sender", "http", "request", "id7")),
        )
        assertFalse("audi" in words)
    }
}
