/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.data

/**
 * Global holder for the currently active game package.
 *
 * This is intentionally a tiny process-wide singleton because many
 * non-injectable UI layers (Composables, legacy fragments, stores)
 * need to know whether the package being edited is the live session.
 */
object ActiveGameHolder {
    @Volatile
    var currentPackage: String? = null
}
