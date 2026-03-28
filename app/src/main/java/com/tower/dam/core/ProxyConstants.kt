package com.tower.dam.core

object ProxyConstants {
    const val BIN_NAME = "sing-box"
    const val CONFIG_NAME = "config.json"

    const val IPTABLES_NAT_CHAIN = "DAM_NAT"
    const val IPTABLES_FILTER_CHAIN = "DAM_FILTER"

    const val ACTION_PROXY_STATE = "com.tower.dam.PROXY_STATE"
    const val EXTRA_PROXY_RUNNING = "proxy_running"
    const val EXTRA_PROXY_SUCCESS = "proxy_success"
    const val EXTRA_PROXY_OPERATION = "proxy_operation"
    const val OP_START = "start"
    const val OP_STOP = "stop"
}
