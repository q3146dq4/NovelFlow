package com.novelflow.app.filter

import android.util.Log

data class CompiledFilterSet(
  val fingerprint: String,
  val networkEngine: NetworkFilterEngine,
  val cosmeticEngine: CosmeticFilterEngine,
)

object FilterCompiler {
  private const val TAG = "FilterCompiler"

  fun compile(snapshot: RuleSetSnapshot): CompiledFilterSet {
    val networkRules = mutableListOf<CompiledNetworkRule>()
    val cosmeticRules = mutableListOf<CompiledCosmeticRule>()
    var skippedNetwork = 0
    var skippedCosmetic = 0

    snapshot.ruleLines.forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEach
      if ("##" in line || "#@#" in line) {
        val rule = CosmeticRuleParser.parse(line)
        if (rule == null) skippedCosmetic += 1 else cosmeticRules += rule
      } else {
        val rule = NetworkRuleParser.parse(line)
        if (rule == null) skippedNetwork += 1 else networkRules += rule
      }
    }

    Log.i(
      TAG,
      "Compiled filters: network=${networkRules.size}, cosmetic=${cosmeticRules.size}, skippedNetwork=$skippedNetwork, skippedCosmetic=$skippedCosmetic",
    )

    return CompiledFilterSet(
      fingerprint = snapshot.fingerprint,
      networkEngine = NetworkFilterEngine.create(networkRules),
      cosmeticEngine = CosmeticFilterEngine.create(cosmeticRules),
    )
  }
}
