package com.tower.dam.core

import android.content.Context

interface IProxyService {
    fun start(context: Context, ip: String, port: Int, uids: Set<Int>, callback: (Boolean) -> Unit)
    fun stop(context: Context, callback: (Boolean) -> Unit)
}