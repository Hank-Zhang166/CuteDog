package com.cutedog.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression for the real API 35 worker-thread Toast crash; no permission mutation or fixtures. */
class MessageDispatchTest {
    @Test fun aBackgroundCompletionCanShowAMessageWithoutPreparingAWorkerLooper() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                assertNull(Looper.myLooper())
                context.message("小狗测试提示：后台操作已结束。")
            }.get(5, TimeUnit.SECONDS)
            // Drain the actual main queue so an asynchronously thrown Toast error cannot hide
            // behind a successfully completed background Future.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } finally { worker.shutdownNow() }
    }
}
