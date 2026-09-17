package com.termfold.app.core

import java.util.UUID

/** Short, collision-free ids; only ever compared for equality. */
object Ids {
    fun new(): String = UUID.randomUUID().toString().take(8)
}
