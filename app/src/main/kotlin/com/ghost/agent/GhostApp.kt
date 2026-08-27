package com.ghost.agent

import android.app.Application

/**
 * No DI container, no eager initialization.
 *
 * Everything expensive -- the model, the perception layer, the overlay -- is owned by
 * the accessibility service and created in its `onServiceConnected`. Loading a 2GB model
 * in `Application.onCreate` would block cold start even when the user only opened the app
 * to read the setup instructions.
 */
class GhostApp : Application()
