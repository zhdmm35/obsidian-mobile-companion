package com.obsidiancompanion.feature.sync

import com.obsidiancompanion.data.mock.ConflictDemo
import com.obsidiancompanion.data.repository.RefreshOutcome
import com.obsidiancompanion.data.repository.RefreshUiState
import com.obsidiancompanion.data.repository.TreeDiff
import com.obsidiancompanion.model.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncUiStateTest {
    @Before
    fun disableConflictDemo() {
        ConflictDemo.enabled = false
    }

    @Test
    fun connectionFailure_doesNotClaimDeviceIsOffline() {
        // 网络仍连接时，DNS / 超时等 IOException 也会映射到 NetworkUnavailable。
        val message = refreshFailureMessage(DomainError.NetworkUnavailable)
        assertFalse(message.contains("当前离线"))
        assertTrue(message.contains("网络"))
    }

    @Test
    fun retryingRefresh_hidesPreviousFailure() {
        val failed = RefreshOutcome.Failed(DomainError.Unauthorized, at = 1)
        val state = SyncUiState(refresh = RefreshUiState(lastOutcome = failed))
        assertEquals(failed, state.failure)
        assertNull(state.copy(refresh = state.refresh.copy(refreshing = true)).failure)
    }

    @Test
    fun successfulRefresh_clearsPreviousFailure() {
        val failed = SyncUiState(refresh = RefreshUiState(lastOutcome = RefreshOutcome.Failed(DomainError.Forbidden, at = 1)))
        val diff = TreeDiff.Result(emptyList(), emptyList(), emptyList(), emptyList(), 0, false)
        assertNull(failed.copy(refresh = RefreshUiState(lastOutcome = RefreshOutcome.Success(diff, at = 2))).failure)
        assertNull(failed.copy(refresh = RefreshUiState(lastOutcome = RefreshOutcome.NotModified(at = 2))).failure)
    }

    @Test
    fun offlineState_doesNotDisplayStaleAuthenticationFailure() {
        val state = SyncUiState(
            refresh = RefreshUiState(lastOutcome = RefreshOutcome.Failed(DomainError.Unauthorized, at = 1)),
            isOffline = true,
        )
        assertNull(state.failure)
    }

    @Test
    fun authenticationMessages_explainHowToRecover() {
        assertTrue(refreshFailureMessage(DomainError.Unauthorized).contains("重新设置 Token"))
        assertTrue(refreshFailureMessage(DomainError.Forbidden).contains("权限"))
    }
}
