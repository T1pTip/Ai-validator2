package com.aiorigin.detector

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Copies androidTest assets into the app's own cache so the app can read them like user files. */
object TestFiles {
    val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    fun fixture(name: String): Uri {
        val dir = File(target.cacheDir, "test-fixtures").apply { mkdirs() }
        val out = File(dir, name)
        testContext.assets.open("fixtures/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    fun asset(path: String): String = testContext.assets.open(path).use { it.readBytes().decodeToString() }
}
