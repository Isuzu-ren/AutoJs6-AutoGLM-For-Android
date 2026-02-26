package com.kevinluo.autoglm.api

import org.json.JSONObject

interface AutoJsToolApi {

    fun listWorkspaceScriptsJson(): JSONObject

    fun runScriptJson(path: String, params: JSONObject? = null): JSONObject

    fun getExecutionStatusJson(executionId: Int): JSONObject
}