package com.obsidiancompanion.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 仓库地址粘贴解析：owner/repo 提取、后缀与多余路径段剥离、非法输入拒绝。 */
class RepoUrlParserTest {

    @Test
    fun `owner slash repo shorthand`() {
        assertEquals("octocat" to "vault", parseRepoInput("octocat/vault"))
    }

    @Test
    fun `full https url`() {
        assertEquals("octocat" to "vault", parseRepoInput("https://github.com/octocat/vault"))
    }

    @Test
    fun `url without scheme`() {
        assertEquals("octocat" to "vault", parseRepoInput("github.com/octocat/vault"))
    }

    @Test
    fun `trailing slash and git suffix stripped`() {
        assertEquals("octocat" to "vault", parseRepoInput("https://github.com/octocat/vault/"))
        assertEquals("octocat" to "vault", parseRepoInput("https://github.com/octocat/vault.git"))
    }

    @Test
    fun `ssh form`() {
        assertEquals("octocat" to "vault", parseRepoInput("git@github.com:octocat/vault.git"))
    }

    @Test
    fun `extra path segments ignored`() {
        assertEquals("octocat" to "vault", parseRepoInput("https://github.com/octocat/vault/tree/main/notes"))
    }

    @Test
    fun `whitespace around input tolerated`() {
        assertEquals("octocat" to "vault", parseRepoInput("  https://github.com/octocat/vault  "))
    }

    @Test
    fun `non-ascii and other hosts rejected`() {
        // GitHub owner 只允许字母数字与连字符；其它站点链接的首段同样因此被挡下
        assertNull(parseRepoInput("张三/笔记"))
        assertNull(parseRepoInput("https://gitlab.com/octocat/vault"))
    }

    @Test
    fun `invalid inputs rejected`() {
        assertNull(parseRepoInput(""))
        assertNull(parseRepoInput("   "))
        assertNull(parseRepoInput("octocat"))
        assertNull(parseRepoInput("https://github.com/"))
    }
}
