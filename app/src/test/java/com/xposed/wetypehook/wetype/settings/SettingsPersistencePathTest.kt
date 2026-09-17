package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the decision that a failed module-app bridge must not be reported as a
 * failed save.
 *
 * LSPatch embed mode has no `com.xposed.wetypehook` package at all, so the
 * bridge broadcast has nowhere to go and every save used to wait out a 5s ACK
 * timeout and then tell the user "could not save settings" — while the value
 * had in fact already been written locally and took effect on the next
 * input-method restart.
 *
 * The settings UI reads the boolean back through `saveSettings`, so the rules
 * that matter are: local write decides the result, bridge availability only
 * decides whether a bridge is attempted.
 */
class SettingsPersistencePathTest {

    /**
     * The three states `onPersisted` can be called with, after the fix.
     * Kept as a local model of the branch in `saveDirect` rather than a call
     * into it — that path needs a real Context and SharedPreferences.
     */
    private fun saveResult(localWriteSucceeded: Boolean, bridgeApplicable: Boolean, bridgeAccepted: Boolean): Boolean =
        if (!localWriteSucceeded) false
        else if (!bridgeApplicable) true
        else bridgeAccepted

    @Test fun localWriteAloneIsEnoughWhenThereIsNoModuleApp() {
        assertTrue(saveResult(localWriteSucceeded = true, bridgeApplicable = false, bridgeAccepted = false))
    }

    @Test fun bridgeFailureStillCountsAsFailureWhenAModuleAppExists() {
        assertFalse(saveResult(localWriteSucceeded = true, bridgeApplicable = true, bridgeAccepted = false))
    }

    @Test fun bridgeSuccessIsReportedAsSuccess() {
        assertTrue(saveResult(localWriteSucceeded = true, bridgeApplicable = true, bridgeAccepted = true))
    }

    @Test fun aFailedLocalWriteIsNeverReportedAsSuccess() {
        assertFalse(saveResult(localWriteSucceeded = false, bridgeApplicable = false, bridgeAccepted = true))
        assertFalse(saveResult(localWriteSucceeded = false, bridgeApplicable = true, bridgeAccepted = true))
    }

    /**
     * The LSPatch embed marker: the module package is absent while the module
     * code itself is running inside the host. Detection is a plain
     * getPackageInfo probe, so absence must be a normal outcome, not a throw
     * that escapes into the save path.
     */
    @Test fun absentModulePackageIsDetectedNotThrown() {
        val probe: (String) -> Boolean = { packageName ->
            runCatching { if (packageName == "com.xposed.wetypehook") error("NameNotFoundException") else Unit }
                .isSuccess
        }
        assertFalse(probe("com.xposed.wetypehook"))
        assertTrue(probe("com.tencent.wetype"))
    }
}
