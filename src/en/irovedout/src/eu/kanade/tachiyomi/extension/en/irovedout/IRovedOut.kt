package eu.kanade.tachiyomi.extension.en.irovedout

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class IRovedOut : HttpSource() {

    override val name = "I Roved Out"
    override val baseUrl = "https://www.irovedout.com"
    override val lang = "en"
    override val supportsLatest = false
    private val archiveUrl = "$baseUrl/archive"
    private val thumbnailUrl = "https://i.ibb.co/2g7Htwq/irovedout.png"
    private val seriesTitle = "I Roved Out in Search of Truth and Love"
    private val authorName = "Alexis Flower"
    private val seriesGenre = "Fantasy"
    private val seriesDescription = """
        I ROVED OUT IN SEARCH OF TRUTH AND LOVE is written & illustrated by Alexis Flower.
        It updates in chunks anywhere between 3 and 30 pages long at least once a month.
    """.trimIndent()
    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    private val titleRegex = Regex("Book (?<bookNumber>\\d+): (?<chapterTitle>.+)")
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private var SharedPreferences.lastUpdatedChapter
        get() = getFloat(PREF_LAST_UPDATED_CHAPTER, PREF_LAST_UPDATED_CHAPTER_DEFAULT)
        set(value) { edit().putFloat(PREF_LAST_UPDATED_CHAPTER, value).commit() }

    private var SharedPreferences.lastUpdatedChapterPages
        get() = getInt(PREF_LAST_UPDATED_CHAPTER_PAGES, PREF_LAST_UPDATED_CHAPTER_PAGES_DEFAULT)
        set(value) { edit().putInt(PREF_LAST_UPDATED_CHAPTER_PAGES, value).commit() }

    private fun getChapterPages(chapter: SChapter): List<Page> {
        val match = titleRegex.matchEntire(chapter.name) ?: return listOf()
        val bookNumber = match.groups["bookNumber"]!!.value.toInt()
        val title = match.groups["chapterTitle"]!!.value
        val bookPage = client.newCall(GET(archiveUrl + if (bookNumber != 1) "-book-$bookNumber" else "", headers)).execute().asJsoup()
        val chapterWrap = bookPage.select(".comic-archive-chapter-wrap").find { it.selectFirst(".comic-archive-chapter")!!.text() == title }
        val pageUrls = chapterWrap?.select(".comic-archive-list-wrap .comic-archive-title > a")?.map { it.attr("href") } ?: return listOf()
        val pages = pageUrls.mapIndexed { pageIndex, pageUrl ->
            Page(pageIndex, pageUrl)
        }
        return pages
    }

    override fun chapterListRequest(manga: SManga): Request = throw Exception("Not used")

    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("Not used")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val mainPage = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        val books = mainPage.select("#menu-menu > li > a[href^=$archiveUrl]")

        var chapterCounter = 1F
        val chaptersByBook = books.mapIndexed { bookIndex, book ->
            val bookNumber = bookIndex + 1
            val bookUrl = book.attr("href")
            val bookPage = client.newCall(GET(bookUrl, headers)).execute().asJsoup()
            val chapters = bookPage.select(".comic-archive-chapter-wrap")
            chapters.map {
                val chapterWrap = it.selectFirst(".comic-archive-chapter-wrap")!!
                val timestamp = dateFormat.parse(chapterWrap.select(".comic-archive-date").last()!!.text())?.time ?: 0L
                val chapter = SChapter.create().apply {
                    name = "Book $bookNumber: ${chapterWrap.selectFirst(".comic-archive-chapter")!!.text()}"
                    chapter_number = chapterCounter++
                    date_upload = timestamp
                    url = chapter_number.toString()
                }

                val isLastUpdatedChapter = chapter.chapter_number.toInt() == preferences.lastUpdatedChapter.toInt()
                if (isLastUpdatedChapter) {
                    chapter.chapter_number = preferences.lastUpdatedChapter
                }
                val pageCount = it.select(".comic-list").count()
                val shouldUpdateChapter = isLastUpdatedChapter && pageCount > preferences.lastUpdatedChapterPages
                if (shouldUpdateChapter) {
                    chapter.apply {
                        chapter_number += 0.1F
                        url = chapter_number.toString()
                    }
                }
                if (chapter.chapter_number > preferences.lastUpdatedChapter || shouldUpdateChapter) {
                    preferences.apply {
                        lastUpdatedChapter = chapter.chapter_number
                        lastUpdatedChapterPages = pageCount
                    }
                }

                chapter
            }
        }
        return Observable.just(chaptersByBook.flatten().reversed())
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val comicPage = client.newCall(GET(page.url, headers)).execute().asJsoup()
        val imageUrl = comicPage.selectFirst("#comic img")!!.attr("src")
        return Observable.just(imageUrl)
    }

    override fun imageUrlParse(response: Response): String = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(manga)

    override fun mangaDetailsParse(response: Response): SManga = throw Exception("Not used")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(getChapterPages(chapter))

    override fun pageListRequest(chapter: SChapter): Request = throw Exception("Not used")

    override fun pageListParse(response: Response): List<Page> = throw Exception("Not used")

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val manga = SManga.create().apply {
            url = ""
            thumbnail_url = thumbnailUrl
            title = seriesTitle
            author = authorName
            artist = authorName
            description = seriesDescription
            genre = seriesGenre
            status = SManga.ONGOING
            initialized = true
        }
        return Observable.just(MangasPage(listOf(manga), false))
    }

    override fun popularMangaParse(response: Response): MangasPage = throw Exception("Not used")

    override fun popularMangaRequest(page: Int): Request = throw Exception("Not used")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = throw Exception("Not used")

    override fun searchMangaParse(response: Response): MangasPage = throw Exception("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("Not used")

    companion object {
        private const val PREF_LAST_UPDATED_CHAPTER = "last_updated_chapter"
        private const val PREF_LAST_UPDATED_CHAPTER_DEFAULT = -1F
        private const val PREF_LAST_UPDATED_CHAPTER_PAGES = "last_updated_chapter_pages"
        private const val PREF_LAST_UPDATED_CHAPTER_PAGES_DEFAULT = -1
    }
}
