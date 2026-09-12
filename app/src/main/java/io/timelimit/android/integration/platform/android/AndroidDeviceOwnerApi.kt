/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.integration.platform.android

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.integration.platform.DeviceOwnerApi

class AndroidDeviceOwnerApi(
    private val componentName: ComponentName,
    private val devicePolicyManager: DevicePolicyManager,
    private val packageManager: PackageManager
): DeviceOwnerApi {
    companion object {
        private const val LOG_TAG = "AndroidDeviceOwnerApi"
    }

    private val delegationList =
        if (VERSION.SDK_INT >= VERSION_CODES.O)
            listOfNotNull(
                Pair(DevicePolicyManager.DELEGATION_APP_RESTRICTIONS, DeviceOwnerApi.DelegationScope("DELEGATION_APP_RESTRICTIONS")),
                Pair(DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL, DeviceOwnerApi.DelegationScope("DELEGATION_BLOCK_UNINSTALL")),
                Pair(DevicePolicyManager.DELEGATION_CERT_INSTALL, DeviceOwnerApi.DelegationScope("DELEGATION_CERT_INSTALL")),
                if (VERSION.SDK_INT >= VERSION_CODES.Q)
                    Pair(DevicePolicyManager.DELEGATION_CERT_SELECTION, DeviceOwnerApi.DelegationScope("DELEGATION_CERT_SELECTION"))
                else
                    null,
                Pair(DevicePolicyManager.DELEGATION_ENABLE_SYSTEM_APP, DeviceOwnerApi.DelegationScope("DELEGATION_ENABLE_SYSTEM_APP")),
                if (VERSION.SDK_INT >= VERSION_CODES.P)
                    Pair(DevicePolicyManager.DELEGATION_INSTALL_EXISTING_PACKAGE, DeviceOwnerApi.DelegationScope("DELEGATION_INSTALL_EXISTING_PACKAGE"))
                else
                    null,
                if (VERSION.SDK_INT >= VERSION_CODES.P)
                    Pair(DevicePolicyManager.DELEGATION_KEEP_UNINSTALLED_PACKAGES, DeviceOwnerApi.DelegationScope("DELEGATION_KEEP_UNINSTALLED_PACKAGES"))
                else
                    null,
                if (VERSION.SDK_INT >= VERSION_CODES.Q)
                    Pair(DevicePolicyManager.DELEGATION_NETWORK_LOGGING, DeviceOwnerApi.DelegationScope("DELEGATION_NETWORK_LOGGING"))
                else
                    null,
                Pair(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS, DeviceOwnerApi.DelegationScope("DELEGATION_PACKAGE_ACCESS")),
                Pair(DevicePolicyManager.DELEGATION_PERMISSION_GRANT, DeviceOwnerApi.DelegationScope("DELEGATION_PERMISSION_GRANT")),
                if (VERSION.SDK_INT >= VERSION_CODES.S)
                    Pair(DevicePolicyManager.DELEGATION_SECURITY_LOGGING, DeviceOwnerApi.DelegationScope("DELEGATION_SECURITY_LOGGING"))
                else
                    null
            )
        else emptyList()

    override val delegations: List<DeviceOwnerApi.DelegationScope> = delegationList.map { it.second }

    override fun setDelegations(packageName: String, scopes: List<DeviceOwnerApi.DelegationScope>) {
        if (BuildConfig.storeCompilant) throw IllegalStateException()
        if (VERSION.SDK_INT <= VERSION_CODES.O) throw IllegalStateException()

        val resolvedScopes = scopes.map { scope ->
            delegationList.find { it.second === scope }!!.first
        }

        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "setDelegations($packageName, $resolvedScopes)")
        }

        devicePolicyManager.setDelegatedScopes(
            componentName,
            packageName,
            resolvedScopes
        )
    }

    override fun getDelegations(): Map<String, List<DeviceOwnerApi.DelegationScope>> {
        if (BuildConfig.storeCompilant) return emptyMap()
        if (VERSION.SDK_INT <= VERSION_CODES.O) throw IllegalStateException()

        return delegationList.map { (scope, delegation) ->
            devicePolicyManager.getDelegatePackages(componentName, scope)?.map { packageName ->
                Pair(packageName, delegation)
            } ?: emptyList()
        }.flatten().groupBy { it.first }.mapValues { entry ->
            entry.value.map { it.second }
        }
    }

    override fun setOrganizationName(name: String) {
        if (VERSION.SDK_INT >= VERSION_CODES.O && !BuildConfig.storeCompilant) {
            if (devicePolicyManager.isDeviceOwnerApp(componentName.packageName)) {
                devicePolicyManager.setOrganizationName(componentName, name)
            } else throw SecurityException()
        } else throw SecurityException()
    }

    override fun transferOwnership(packageName: String, dryRun: Boolean) {
        if (BuildConfig.storeCompilant) throw IllegalStateException()
        if (VERSION.SDK_INT < VERSION_CODES.P) throw IllegalStateException()
        if (!devicePolicyManager.isDeviceOwnerApp(componentName.packageName)) throw SecurityException()

        val targetComponentName = packageManager.queryBroadcastReceivers(
            Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
                .setPackage(packageName),
            0
        ).singleOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }

        if (targetComponentName == null) throw RuntimeException("no device admin in the target App found")

        if (dryRun) return

        devicePolicyManager.setDelegatedScopes(componentName, packageName, emptyList())
        devicePolicyManager.transferOwnership(componentName, targetComponentName, null)
    }

    override fun grantLocationAccess(): Boolean {
        if (BuildConfig.storeCompilant) return false
        if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) return false
        if (!devicePolicyManager.isDeviceOwnerApp(componentName.packageName)) return false

        try {
            return devicePolicyManager.setPermissionGrantState(
                componentName, componentName.packageName, Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (ex: SecurityException) {
            // set to default so that granting this manually is possible
            devicePolicyManager.setPermissionGrantState(
                componentName, componentName.packageName, Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            )

            return false
        }
    }

    // @tag:url-filter @tag:device-owner
    override fun setApplicationRestrictions(packageName: String, restrictions: Map<String, Any>): Boolean {
        if (BuildConfig.storeCompilant) return false
        if (!devicePolicyManager.isDeviceOwnerApp(componentName.packageName)) return false

        return try {
            val bundle = Bundle()

            restrictions.forEach { (key, value) ->
                when (value) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                    is List<*> -> bundle.putStringArray(key, value.map { it as String }.toTypedArray())
                    else -> throw IllegalArgumentException("unsupported restriction type for $key")
                }
            }

            devicePolicyManager.setApplicationRestrictions(componentName, packageName, bundle)

            true
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "setApplicationRestrictions($packageName) failed", ex)

            false
        }
    }
}
