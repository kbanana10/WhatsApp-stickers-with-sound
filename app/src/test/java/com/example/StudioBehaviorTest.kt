package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnalysisResult
import com.example.engine.CapturedFrame
import com.example.engine.CellStatus
import com.example.engine.FramingMode
import com.example.engine.MediaEngine
import com.example.engine.MixFitMode
import com.example.engine.SizeCapMode
import com.example.engine.SourceKind
import com.example.engine.StickerLimits
import com.example.ui.viewmodel.StudioMode
import com.example.ui.viewmodel.StudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StudioBehaviorTest {

    @Test
    fun recomputeSizeCap_updatesStatusesAndRecommendationWithoutRedecodingFrames() {
        val dummyBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val dummyFrames = listOf(
            CapturedFrame(dummyBitmap, ByteArray(256) { 1 }),
            CapturedFrame(dummyBitmap, ByteArray(256) { 2 })
        )
        val sampleBytes = mapOf(
            512 to mapOf(
                0.75f to 3000.0,
                0.35f to 2100.0,
                StickerLimits.Q_MIN to 1400.0
            ),
            256 to mapOf(
                0.55f to 1200.0,
                0.35f to 900.0,
                StickerLimits.Q_MIN to 600.0
            )
        )
        val distinctCounts = mapOf(20 to 200, 10 to 100)
        val initial = AnalysisResult(
            sourceKind = SourceKind.VIDEO,
            framingMode = FramingMode.CONTAIN,
            fitMode = MixFitMode.INDEPENDENT,
            sizeCapMode = SizeCapMode.MAX_QUALITY_1MB,
            durationSec = 10.0,
            visualDurationSec = 10.0,
            audioDurationSec = 10.0,
            frames = dummyFrames,
            captureFps = 20,
            captureSide = 512,
            distinctCounts = distinctCounts,
            sampleBytes = sampleBytes,
            cells = emptyList(),
            recommended = null,
            elapsedMs = 123L
        )

        val under1Mb = MediaEngine.recomputeSizeCap(initial, SizeCapMode.MAX_QUALITY_1MB)
        val cell512at20_1Mb = under1Mb.cells.first { it.side == 512 && it.fps == 20 }
        assertEquals(CellStatus.OK, cell512at20_1Mb.status)
        assertEquals(512, under1Mb.recommended?.side)
        assertEquals(20, under1Mb.recommended?.fps)
        assertSame(dummyFrames, under1Mb.frames)

        val under500Kb = MediaEngine.recomputeSizeCap(under1Mb, SizeCapMode.WHATSAPP_PACK_500KB)
        val cell512at20_500Kb = under500Kb.cells.first { it.side == 512 && it.fps == 20 }
        assertEquals(CellStatus.LOW_QUALITY, cell512at20_500Kb.status)
        assertNotNull(under500Kb.recommended)
        assertTrue(under500Kb.recommended!!.status == CellStatus.OK)
        assertEquals(SizeCapMode.WHATSAPP_PACK_500KB, under500Kb.sizeCapMode)
        assertEquals(0L, under500Kb.elapsedMs)
        assertSame(dummyFrames, under500Kb.frames)
    }

    @Test
    fun studioViewModel_optionChangesNeverTriggerAutomaticAnalysis() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = StudioViewModel(app)

        vm.selectMode(StudioMode.VIDEO)
        vm.setFramingMode(FramingMode.COVER)
        vm.setMixFitMode(MixFitMode.REPEAT)
        vm.setSizeCapMode(SizeCapMode.WHATSAPP_PACK_500KB)

        val state = vm.uiState.value
        assertFalse(state.isAnalyzing)
        assertNull(state.analysis)
        assertEquals(FramingMode.COVER, state.framingMode)
        assertEquals(MixFitMode.REPEAT, state.mixFitMode)
        assertEquals(SizeCapMode.WHATSAPP_PACK_500KB, state.sizeCapMode)
    }
}
