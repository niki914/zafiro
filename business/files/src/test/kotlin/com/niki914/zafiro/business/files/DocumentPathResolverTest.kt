package com.niki914.zafiro.business.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 路径规则的表测试。纯字符串层（[DocumentPathResolver.toPath]），
 * 不碰 `Uri` / `Environment` —— 那两个只在 [DocumentPathResolver.resolve] 里做取值。
 */
class DocumentPathResolverTest {

    private val root = "/storage/emulated/0"

    private fun doc(docId: String) = DocumentPathResolver.toPath(docId, root, isTree = false)
    private fun tree(docId: String) = DocumentPathResolver.toPath(docId, root, isTree = true)

    @Test
    fun `primary document maps into the primary root`() {
        assertEquals("$root/adbi/pkg/logs", doc("primary:adbi/pkg/logs"))
    }

    @Test
    fun `primary tree keeps a trailing slash`() {
        assertEquals("$root/adbi/", tree("primary:adbi"))
    }

    @Test
    fun `primary document does not grow a trailing slash`() {
        assertEquals("$root/Download/report.pdf", doc("primary:Download/report.pdf"))
    }

    @Test
    fun `whole primary volume maps to the root itself`() {
        assertEquals(root, doc("primary:"))
        assertEquals("$root/", tree("primary:"))
    }

    @Test
    fun `redundant slashes are normalised`() {
        assertEquals("$root/adbi", doc("primary:/adbi/"))
    }

    @Test
    fun `non primary volumes are rejected`() {
        // SD 卡卷：docId 是 <UUID>:<相对路径>，本次不支持
        assertNull(doc("1A2B-3C4D:DCIM/photo.jpg"))
        assertNull(tree("1A2B-3C4D:DCIM"))
    }

    @Test
    fun `raw document uses the absolute path the provider gave us`() {
        assertEquals("/storage/emulated/0/Download/report.pdf", doc("raw:/storage/emulated/0/Download/report.pdf"))
    }

    @Test
    fun `raw tree keeps a trailing slash`() {
        assertEquals("/storage/emulated/0/Download/", tree("raw:/storage/emulated/0/Download"))
    }

    @Test
    fun `raw docIds that are not absolute paths are rejected`() {
        assertNull(doc("raw:relative/report.pdf"))
        assertNull(doc("raw:"))
        assertNull(doc("raw:/"))
    }

    @Test
    fun `download manager record ids are rejected`() {
        // 下载抽屉里在 DownloadManager 数据库中的文件：裸数字 id，路径只有该 provider 自己知道
        assertNull(doc("5"))
        assertNull(tree("5"))
    }

    @Test
    fun `docId without a volume separator is rejected`() {
        assertNull(doc("nocolon"))
        assertNull(doc(""))
        assertNull(doc("home:whatever"))
    }
}
