package app.genkan.ui

import app.genkan.dictionary.Dictionaries
import app.genkan.dictionary.DictionaryEntity
import app.genkan.input.PadKey
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionariesViewModelTest {

    private fun dictionary(id: Long, title: String, enabled: Boolean = true) = DictionaryEntity(
        id = id, title = title, revision = "", folder = "/d$id", sourceName = "$title.zip",
        terms = 1, frequencies = 0, pitches = 0, kanji = 0, position = id.toInt(), enabled = enabled, importedAt = 0,
    )

    private val jmdict = dictionary(1, "JMdict")
    private val shinwaei = dictionary(2, "新和英", enabled = false)
    private val dictionaries = mockk<Dictionaries>(relaxed = true) {
        every { all } returns MutableStateFlow(listOf(jmdict, shinwaei))
    }
    private lateinit var viewModel: DictionariesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = DictionariesViewModel(dictionaries)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `focus stays between the import row and the last dictionary`() {
        viewModel.onKey(PadKey.UP)
        assertEquals(0, viewModel.focusIndex.value)
        repeat(5) { viewModel.onKey(PadKey.DOWN) }
        assertEquals(2, viewModel.focusIndex.value)
    }

    @Test
    fun `A switches the focused dictionary`() {
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.A)
        coVerify { dictionaries.setEnabled(jmdict, false) }
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.A)
        coVerify { dictionaries.setEnabled(shinwaei, true) }
    }

    @Test
    fun `L1 and R1 move the dictionary and the focus with it`() {
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.L1)
        coVerify { dictionaries.move(shinwaei, -1) }
        assertEquals(1, viewModel.focusIndex.value)
    }

    @Test
    fun `moving the import row does nothing`() {
        viewModel.onKey(PadKey.L1)
        coVerify(exactly = 0) { dictionaries.move(any(), any()) }
    }

    @Test
    fun `X asks first, then removes`() {
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.X)
        assertEquals(jmdict.id, viewModel.armedRemoval.value)
        coVerify(exactly = 0) { dictionaries.remove(any()) }
        viewModel.onKey(PadKey.X)
        coVerify { dictionaries.remove(jmdict) }
        assertNull(viewModel.armedRemoval.value)
    }

    @Test
    fun `moving away cancels a removal`() {
        viewModel.onKey(PadKey.DOWN)
        viewModel.onKey(PadKey.X)
        viewModel.onKey(PadKey.DOWN)
        assertNull(viewModel.armedRemoval.value)
    }
}
