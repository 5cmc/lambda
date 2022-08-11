package com.lambda.client.util

import com.lambda.client.LambdaMod
import com.lambda.client.commons.utils.ConnectionUtils
import java.awt.Desktop
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI

object WebUtils {
    var isLatestVersion = true
    var latestVersion: String? = null

    fun openWebLink(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                val exitCode = Runtime.getRuntime().exec(arrayOf("xdg-open", url)).waitFor()
                if (exitCode != 0) {
                    LambdaMod.LOG.error("Couldn't open link, xdg-open returned: $exitCode")
                }
            }
        } catch (e: IOException) {
            LambdaMod.LOG.error("Couldn't open link: $url")
        }
    }

    fun getUrlContents(url: String): String {
        val content = StringBuilder()

        ConnectionUtils.runConnection(url, block = { connection ->
            val bufferedReader = BufferedReader(InputStreamReader(connection.inputStream))
            bufferedReader.forEachLine { content.append("$it\n") }
        }, catch = {
            it.printStackTrace()
        })

        return content.toString()
    }
}