package com.squareup.invert.internal.report

import com.squareup.invert.internal.models.CollectedConfigurationsForProject
import com.squareup.invert.internal.models.CollectedDependenciesForProject
import com.squareup.invert.internal.models.CollectedOwnershipForProject
import com.squareup.invert.internal.models.CollectedPluginsForProject
import com.squareup.invert.internal.models.CollectedStatsForProject
import com.squareup.invert.internal.report.js.InvertJsReportUtils
import com.squareup.invert.internal.report.js.InvertJsReportUtils.computeGlobalTotals
import com.squareup.invert.internal.report.js.InvertJsReportWriter
import com.squareup.invert.internal.report.json.InvertJsonReportWriter
import com.squareup.invert.internal.report.sarif.InvertSarifReportWriter
import com.squareup.invert.logging.InvertLogger
import com.squareup.invert.models.DependencyId
import com.squareup.invert.models.ModulePath
import com.squareup.invert.models.js.CollectedStatTotalsJsReportModel
import com.squareup.invert.models.js.HistoricalData
import com.squareup.invert.models.js.MetadataJsReportModel
import java.io.File

class InvertReportWriter(
  private val invertLogger: InvertLogger,
  private val rootBuildReportsDir: File
) {
  fun writeProjectData(
    reportMetadata: MetadataJsReportModel,
    collectedOwners: Set<CollectedOwnershipForProject>,
    collectedStats: Set<CollectedStatsForProject>,
    collectedDependencies: Set<CollectedDependenciesForProject>,
    collectedConfigurations: Set<CollectedConfigurationsForProject>,
    collectedPlugins: Set<CollectedPluginsForProject>,
    historicalData: Set<HistoricalData>,
  ) {
    val collectedOwnershipInfo = InvertJsReportUtils.buildModuleToOwnerMap(collectedOwners)
    val allProjectsStatsData = InvertJsReportUtils.buildModuleToStatsMap(collectedStats)
    val directDependenciesJsReportModel = InvertJsReportUtils.toDirectDependenciesJsReportModel(collectedDependencies)
    val invertedDependenciesJsReportModel =
      InvertJsReportUtils.toInvertedDependenciesJsReportModel(collectedDependencies)

    assertModuleMatch(
      logger = invertLogger,
      modulesList = collectedDependencies.map { it.path },
      invertedModulesList = invertedDependenciesJsReportModel.getAllModulePaths(reportMetadata.buildSystem)
    )

    val globalStats = computeGlobalTotals(allProjectsStatsData, collectedOwnershipInfo)

    val historicalDataWithCurrent = (historicalData + HistoricalData(
      reportMetadata = reportMetadata,
      statTotalsAndMetadata = CollectedStatTotalsJsReportModel(globalStats)
    )).sortedBy { it.reportMetadata.latestCommitTime }.toSet()

    // JSON Report
    InvertJsonReportWriter(invertLogger, rootBuildReportsDir).createInvertJsonReport(
      reportMetadata = reportMetadata,
      allConfigurationsData = collectedConfigurations,
      allProjectsDependencyData = collectedDependencies,
      allProjectsStatsData = allProjectsStatsData,
      allPluginsData = collectedPlugins,
      allOwnersData = collectedOwners,
      globalStats = globalStats,
      historicalData = historicalDataWithCurrent,
    )

    // Include all stats into one SARIF report.
    InvertSarifReportWriter(invertLogger, rootBuildReportsDir).createInvertSarifReport(
      allProjectsStatsData = allProjectsStatsData
    )

    // HTML/JS Report
    InvertJsReportWriter(invertLogger, rootBuildReportsDir).createInvertHtmlReport(
      reportMetadata = reportMetadata,
      allProjectsDependencyData = collectedDependencies,
      allProjectsStatsData = allProjectsStatsData,
      directDependencies = directDependenciesJsReportModel,
      invertedDependencies = invertedDependenciesJsReportModel,
      allPluginsData = collectedPlugins,
      collectedOwnershipInfo = collectedOwnershipInfo,
      allProjectsConfigurationsData = collectedConfigurations,
      globalStatTotals = CollectedStatTotalsJsReportModel(globalStats),
      historicalData = historicalDataWithCurrent,
    )
  }

  /**
   * This provides a warning to the user to let them know that a module was found as a dependency
   * but was not scanned itself.  In order to get a full picture of the project, all should
   * be scanned.
   */
  private fun assertModuleMatch(
    logger: InvertLogger,
    modulesList: List<ModulePath>,
    invertedModulesList: List<DependencyId>
  ) {
    if (!invertedModulesList.containsAll(modulesList)) {
      val modulesMap = modulesList.groupBy { it }
      val invertedModulesMap = invertedModulesList.groupBy { it }
      val errorString = buildString {
        appendLine("WARNING: Module Mismatch...")
        appendLine("The following modules are dependencies, but were not scanned:")
        var idx = 1
        invertedModulesMap.keys.sorted().forEach { path ->
          if (modulesMap[path] == null) {
            appendLine("${idx++}. $path")
          }
        }
      }

      logger.warn(errorString)
    }
  }
}
