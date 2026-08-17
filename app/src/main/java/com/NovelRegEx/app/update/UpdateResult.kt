package com.NovelRegEx.app.update

sealed class UpdateResult {
  data class Available(
    val info: UpdateInfo,
  ) : UpdateResult()

  object UpToDate : UpdateResult()

  object Error : UpdateResult()
}
