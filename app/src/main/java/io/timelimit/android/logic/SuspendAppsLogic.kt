/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
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
package io.timelimit.android.logic

import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.runAsyncExpectForever
import io.timelimit.android.data.invalidation.Observer
import io.timelimit.android.data.invalidation.Table
import io.timelimit.android.data.model.CategoryApp
import io.timelimit.android.data.model.ConsentFlags
import io.timelimit.android.data.model.ExperimentalFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.android.AndroidFeatures
import io.timelimit.android.integration.platform.android.AndroidIntegrationApps
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SuspendAppsLogic(private val appLogic: AppLogic): Observer {
    private var lastDefaultCategory: String? = null
    private var lastAllowedPackages: Set<String>? = null
    private var lastAllowedCategoryList = emptySet<String>()
    private var lastCategoryApps = emptyList<CategoryApp>()
    private val installedAppsModified = AtomicBoolean(false)
    private val categoryHandlingCache = CategoryHandlingCache()
    private val realTime = RealTime.newInstance()
    private var batteryStatus = appLogic.platformIntegration.getBatteryStatus()
    private val pendingSync = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor()
    private var lastEnableBlockingAtSystemLevel = false
    private var lastSuspendedApps: List<String>? = null
    private var lastBlockedFeatures: Set<String>? = null
    private val userAndDeviceRelatedDataLive = appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataLive()
    private var didLoadUserAndDeviceRelatedData = false

    private val backgroundRunnable = Runnable {
        while (pendingSync.getAndSet(false)) {
            updateBlockingSync()

            Thread.sleep(500)
        }
    }

    private val triggerRunnable = Runnable {
        triggerUpdate()
    }

    private fun triggerUpdate() {
        pendingSync.set(true); executor.submit(backgroundRunnable)
    }

    private fun scheduleUpdate(delay: Long) {
        appLogic.timeApi.cancelScheduledAction(triggerRunnable)
        appLogic.timeApi.runDelayedByUptime(triggerRunnable, delay)
    }

    init {
        appLogic.database.registerWeakObserver(arrayOf(Table.App), WeakReference(this))
        appLogic.platformIntegration.getBatteryStatusLive().observeForever { batteryStatus = it; triggerUpdate() }
        appLogic.realTimeLogic.registerTimeModificationListener { triggerUpdate() }
        runAsyncExpectForever { appLogic.currentDeviceLogic.borrowedCurrentDevice.collect { triggerUpdate() } }
        userAndDeviceRelatedDataLive.observeForever { didLoadUserAndDeviceRelatedData = true; triggerUpdate() }
    }

    override fun onInvalidated(tables: Set<Table>) {
        installedAppsModified.set(true); triggerUpdate()
    }

    private fun updateBlockingSync() {
        if (!didLoadUserAndDeviceRelatedData) return

        val hasPermission = appLogic.platformIntegration.getCurrentProtectionLevel() == ProtectionLevel.DeviceOwner

        if (!hasPermission) {
            lastDefaultCategory = null
            lastAllowedCategoryList = emptySet()
            lastCategoryApps = emptyList()
            lastSuspendedApps = emptyList()
            lastBlockedFeatures = emptySet()

            return
        }

        val userAndDeviceRelatedData = userAndDeviceRelatedDataLive.value
        val featureCategoryApps = userAndDeviceRelatedData?.userRelatedData?.categoryApps.orEmpty()
            .filter {
                val relatedToDevice = it.appSpecifier.deviceId == null || it.appSpecifier.deviceId == userAndDeviceRelatedData?.deviceRelatedData?.deviceEntry?.id
                val featureSpecifier = it.appSpecifier.packageName.startsWith(DummyApps.FEATURE_APP_PREFIX) && it.appSpecifier.activityName == null

                relatedToDevice && featureSpecifier
            }

        val isRestrictedUser = userAndDeviceRelatedData?.userRelatedData?.user?.type == UserType.Child
        val enableBlockingAtSystemLevel = userAndDeviceRelatedData?.deviceRelatedData?.isExperimentalFlagSetSync(ExperimentalFlags.SYSTEM_LEVEL_BLOCKING) ?: false
        val hasManagedFeatures = featureCategoryApps.isNotEmpty()
        val enableBlocking = isRestrictedUser && (enableBlockingAtSystemLevel || hasManagedFeatures)

        val blockUserSwitchByDefault =
            userAndDeviceRelatedData?.deviceRelatedData?.isConsentFlagSet(ConsentFlags.BLOCK_USER_SWITCH_BY_DEFAULT) == true
                    && userAndDeviceRelatedData.userRelatedData?.user?.type == UserType.Child

        val featureToAllowDefaults = mapOf(
            AndroidFeatures.FEATURE_ADD_USER to false,
            AndroidFeatures.FEATURE_USER_SWITCH to !blockUserSwitchByDefault
        )

        if (!enableBlocking) {
            lastDefaultCategory = null
            lastAllowedCategoryList = emptySet()
            lastCategoryApps = emptyList()
            applySuspendedApps(emptyList())
            applyBlockedFeatures(
                featureToAllowDefaults.filter { !it.value }.map { it.key }.toSet()
            )

            return
        }

        val userRelatedData = userAndDeviceRelatedData.userRelatedData

        val latch = CountDownLatch(1)

        Threads.mainThreadHandler.post { appLogic.realTimeLogic.getRealTime(realTime); latch.countDown() }

        latch.await()

        categoryHandlingCache.reportStatus(
                user = userRelatedData,
                shouldTrustTimeTemporarily = realTime.shouldTrustTimeTemporarily,
                timeInMillis = realTime.timeInMillis,
                batteryStatus = batteryStatus,
                assumeCurrentDevice = CurrentDeviceLogic.handleDeviceAsCurrentDevice(
                    deviceAndUserRelatedData = userAndDeviceRelatedData,
                    borrowedPrimaryDevice = appLogic.currentDeviceLogic.borrowedCurrentDevice.value
                ) is CurrentDeviceLogic.HandleAsCurrentDevice.Yes,
                currentNetworkId = null, // not relevant/ not suspending Apps if there is no matching network
                hasPremiumOrLocalMode = userAndDeviceRelatedData.deviceRelatedData.isLocalMode || userAndDeviceRelatedData.deviceRelatedData.isConnectedAndHasPremium
        )

        val defaultCategory = userRelatedData.user.categoryForNotAssignedApps
        val blockingAtActivityLevel = userAndDeviceRelatedData.deviceRelatedData.deviceEntry.enableActivityLevelBlocking
        val categoryApps = userRelatedData.categoryApps
        val categoryHandlings = userRelatedData.categoryById.keys.map { categoryHandlingCache.get(it) }
        val categoryIdsToAllow = categoryHandlings.filterNot { it.shouldBlockAtSystemLevel }.map { it.createdWithCategoryRelatedData.category.id }.toMutableSet()

        var didModify: Boolean

        do {
            didModify = false

            val iterator = categoryIdsToAllow.iterator()

            for (categoryId in iterator) {
                val parentCategory = userRelatedData.categoryById[userRelatedData.categoryById[categoryId]?.category?.parentCategoryId]

                if (parentCategory != null && !categoryIdsToAllow.contains(parentCategory.category.id)) {
                    iterator.remove(); didModify = true
                }
            }
        } while (didModify)

        // @tag:app-allowance
        val allowances = userRelatedData.user.appAllowances.filter { realTime.shouldTrustTimeTemporarily && it.until > realTime.timeInMillis }
        val allowedPackages = allowances.map { it.packageName }.toSet()

        (categoryHandlings.map { it.dependsOnMaxTime } + allowances.map { it.until }).minOrNull()?.let {
            scheduleUpdate(it - realTime.timeInMillis)
        }

        if (
                categoryIdsToAllow != lastAllowedCategoryList || categoryApps != lastCategoryApps ||
                installedAppsModified.getAndSet(false) || defaultCategory != lastDefaultCategory ||
                allowedPackages != lastAllowedPackages ||
                enableBlockingAtSystemLevel != lastEnableBlockingAtSystemLevel
        ) {
            val appsToBlock = if (enableBlockingAtSystemLevel) {
                val installedApps = appLogic.platformIntegration.getLocalAppPackageNames()
                val prepared = getAppsWithCategories(installedApps, userRelatedData, blockingAtActivityLevel, userAndDeviceRelatedData.deviceRelatedData.deviceEntry.id)
                val appsToBlock = mutableListOf<String>()

                installedApps.forEach { packageName ->
                    val appCategories = prepared[packageName] ?: emptySet()

                    val allowedDespiteCategories = allowedPackages.contains(packageName) && appCategories.none { categoryId ->
                        categoryHandlingCache.get(categoryId).let { !it.okByBattery || !it.okByTempBlocking }
                    }

                    if (!allowedDespiteCategories && appCategories.find { categoryId -> categoryIdsToAllow.contains(categoryId) } == null) {
                        if (!AndroidIntegrationApps.appsToNotSuspend.contains(packageName)) {
                            appsToBlock.add(packageName)
                        }
                    }
                }

                appsToBlock
            } else emptyList()

            val deviceSpecificFeatures = featureCategoryApps.filter { it.appSpecifier.deviceId != null }
            val deviceSpecificFeatureIdentifiers = deviceSpecificFeatures.map { it.appSpecifierString }.toSet()
            val globalFeatures = featureCategoryApps.filter { !deviceSpecificFeatureIdentifiers.contains(it.appSpecifierString) }
            val effectiveFeatures = deviceSpecificFeatures + globalFeatures

            val featuresToAllow = featureToAllowDefaults + effectiveFeatures.associate {
                Pair(
                    it.appSpecifierString.substring(DummyApps.FEATURE_APP_PREFIX.length),
                    categoryIdsToAllow.contains(it.categoryId)
                )
            }

            val featuresToBlock = featuresToAllow.filter { !it.value }.map { it.key }.toSet()

            applySuspendedApps(appsToBlock)
            applyBlockedFeatures(featuresToBlock)

            lastAllowedCategoryList = categoryIdsToAllow
            lastCategoryApps = categoryApps
            lastDefaultCategory = defaultCategory
            lastAllowedPackages = allowedPackages
            lastEnableBlockingAtSystemLevel = enableBlockingAtSystemLevel
        }
    }

    private fun getAppsWithCategories(packageNames: List<String>, data: UserRelatedData, blockingAtActivityLevel: Boolean, deviceId: String): Map<String, Set<String>> {
        val categoryForUnassignedApps = data.categoryById[data.user.categoryForNotAssignedApps]
        val categoryForOtherSystemApps = data.findCategoryAppTryDeviceSpecificFirst(
            packageName = DummyApps.NOT_ASSIGNED_SYSTEM_IMAGE_APP,
            activityName = null,
            deviceId = deviceId
        )?.categoryId?.let { data.categoryById[it] }

        val globalCategoryApps = data.categoryApps.filter { it.appSpecifier.deviceId == null }
        val localCategoryApps = data.categoryApps.filter { it.appSpecifier.deviceId == deviceId }
        val localCategoryAppsParams = localCategoryApps.map { it.appSpecifier.packageName to it.appSpecifier.activityName }.toSet()
        val effectiveGlobalCategoryApps = globalCategoryApps.filter {
            !localCategoryAppsParams.contains(it.appSpecifier.packageName to it.appSpecifier.activityName) &&
            !localCategoryAppsParams.contains(it.appSpecifier.packageName to null)
        }
        val effectiveCategoryApps = effectiveGlobalCategoryApps + localCategoryApps

        if (blockingAtActivityLevel) {
            val categoriesByPackageName = effectiveCategoryApps.groupBy { it.appSpecifier.packageName }

            val result = mutableMapOf<String, Set<String>>()

            packageNames.forEach { packageName ->
                val categoriesItems = categoriesByPackageName[packageName]
                val categories = (categoriesItems?.map { it.categoryId }?.toSet() ?: emptySet()).toMutableSet()
                val isMainAppIncluded = categoriesItems?.find { it.appSpecifier.activityName == null } != null

                if (!isMainAppIncluded) {
                    if (categoryForOtherSystemApps != null && appLogic.platformIntegration.isSystemImageApp(packageName)) {
                        categories.add(categoryForOtherSystemApps.category.id)
                    } else if (categoryForUnassignedApps != null) {
                        categories.add(categoryForUnassignedApps.category.id)
                    }
                }

                result[packageName] = categories
            }

            return result
        } else {
            val categoryByPackageName = effectiveCategoryApps
                .filter { it.appSpecifier.activityName == null }
                .associateBy { it.appSpecifier.packageName }

            val result = mutableMapOf<String, Set<String>>()

            packageNames.forEach { packageName ->
                val category = categoryByPackageName[packageName]?.categoryId ?: run {
                    if (categoryForOtherSystemApps != null && appLogic.platformIntegration.isSystemImageApp(packageName))
                        categoryForOtherSystemApps.category.id else categoryForUnassignedApps?.category?.id
                }

                result[packageName] = if (category != null) setOf(category) else emptySet()
            }

            return result
        }
    }

    private fun applySuspendedApps(packageNames: List<String>) {
        if (packageNames == lastSuspendedApps) {
            // nothing to do
        } else if (packageNames.isEmpty()) {
            appLogic.platformIntegration.stopSuspendingForAllApps()
            lastSuspendedApps = emptyList()
        } else {
            val allApps = appLogic.platformIntegration.getLocalAppPackageNames()
            val appsToNotBlock = allApps.subtract(packageNames)

            appLogic.platformIntegration.setSuspendedApps(appsToNotBlock.toList(), false)
            appLogic.platformIntegration.setSuspendedApps(packageNames, true)
            lastSuspendedApps = packageNames
        }
    }

    private fun applyBlockedFeatures(featureNames: Set<String>) {
        if (featureNames == lastBlockedFeatures) return // nothing to do

        appLogic.platformIntegration.setBlockedFeatures(featureNames)

        lastBlockedFeatures = featureNames
    }
}